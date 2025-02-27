/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.elasticsearch.client;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.JsonCodec;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.log.Logger;
import io.airlift.security.pem.PemReader;
import io.airlift.units.Duration;
import io.prestosql.elasticsearch.AwsSecurityConfig;
import io.prestosql.elasticsearch.ElasticsearchConfig;
import io.prestosql.spi.PrestoException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.prestosql.elasticsearch.ElasticsearchConfig.Security.X_PACK;
import static io.prestosql.elasticsearch.ElasticsearchErrorCode.ELASTICSEARCH_CONNECTION_ERROR;
import static io.prestosql.elasticsearch.ElasticsearchErrorCode.ELASTICSEARCH_INVALID_RESPONSE;
import static io.prestosql.elasticsearch.ElasticsearchErrorCode.ELASTICSEARCH_QUERY_FAILURE;
import static io.prestosql.elasticsearch.ElasticsearchErrorCode.ELASTICSEARCH_SSL_INITIALIZATION_FAILURE;
import static java.lang.StrictMath.toIntExact;
import static java.lang.String.format;
import static java.util.Collections.list;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.elasticsearch.action.search.SearchType.QUERY_THEN_FETCH;

public class ElasticsearchClient
{
    private static final Logger LOG = Logger.get(ElasticsearchClient.class);

    private static final JsonCodec<SearchShardsResponse> SEARCH_SHARDS_RESPONSE_CODEC = jsonCodec(SearchShardsResponse.class);
    private static final JsonCodec<NodesResponse> NODES_RESPONSE_CODEC = jsonCodec(NodesResponse.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();

    private final RestHighLevelClient client;
    private final int scrollSize;
    private final Duration scrollTimeout;

    private final AtomicReference<Set<ElasticsearchNode>> nodes = new AtomicReference<>(ImmutableSet.of());
    private final ScheduledExecutorService executor = newSingleThreadScheduledExecutor(daemonThreadsNamed("NodeRefresher"));
    private final AtomicBoolean started = new AtomicBoolean();
    private final Duration refreshInterval;
    private final boolean tlsEnabled;

    @Inject
    public ElasticsearchClient(ElasticsearchConfig config, Optional<AwsSecurityConfig> awsSecurityConfig)
    {
        requireNonNull(config, "config is null");

        client = createClient(config, awsSecurityConfig);

        this.scrollSize = config.getScrollSize();
        this.scrollTimeout = config.getScrollTimeout();
        this.refreshInterval = config.getNodeRefreshInterval();
        this.tlsEnabled = config.isTlsEnabled();
    }

    @PostConstruct
    public void initialize()
    {
        if (!started.getAndSet(true)) {
            // do the first refresh eagerly
            refreshNodes();

            executor.scheduleWithFixedDelay(this::refreshNodes, refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void close()
            throws IOException
    {
        executor.shutdownNow();
        client.close();
    }

    private void refreshNodes()
    {
        // discover other nodes in the cluster and add them to the client
        try {
            Set<ElasticsearchNode> nodes = fetchNodes();

            HttpHost[] hosts = nodes.stream()
                    .map(ElasticsearchNode::getAddress)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(address -> HttpHost.create(format("%s://%s", tlsEnabled ? "https" : "http", address)))
                    .toArray(HttpHost[]::new);

            if (hosts.length > 0) {
                client.getLowLevelClient().setHosts(hosts);
            }

            this.nodes.set(nodes);
        }
        catch (Throwable e) {
            // Catch all exceptions here since throwing an exception from executor#scheduleWithFixedDelay method
            // suppresses all future scheduled invocations
            LOG.error(e, "Error refreshing nodes");
        }
    }

    private static RestHighLevelClient createClient(ElasticsearchConfig config, Optional<AwsSecurityConfig> awsSecurityConfig)
    {
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(config.getHost(), config.getPort(), config.isTlsEnabled() ? "https" : "http"))
                .setRequestConfigCallback(
                        configBuilder -> configBuilder
                                .setConnectTimeout(toIntExact(config.getConnectTimeout().toMillis()))
                                .setSocketTimeout(toIntExact(config.getRequestTimeout().toMillis())))
                .setMaxRetryTimeoutMillis((int) config.getMaxRetryTime().toMillis());

        builder.setHttpClientConfigCallback(clientBuilder -> {
            if (config.getSecurity().isPresent()) {
                if (config.getSecurity().get().equals(X_PACK)) {
                    CredentialsProvider xPackCredentialsProvider = getXPackCredentialsProvider(config);
                    if (xPackCredentialsProvider != null) {
                        clientBuilder.setDefaultCredentialsProvider(xPackCredentialsProvider);
                    }
                }
            }

            if (config.isTlsEnabled()) {
                buildSslContext(config.getKeystorePath(), config.getKeystorePassword(), config.getTrustStorePath(), config.getTruststorePassword())
                        .ifPresent(clientBuilder::setSSLContext);

                if (config.isVerifyHostnames()) {
                    clientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                }
            }

            awsSecurityConfig.ifPresent(securityConfig -> clientBuilder.addInterceptorLast(new AwsRequestSigner(
                    securityConfig.getRegion(),
                    getAwsCredentialsProvider(securityConfig))));

            return clientBuilder;
        });

        return new RestHighLevelClient(builder);
    }

    private static CredentialsProvider getXPackCredentialsProvider(ElasticsearchConfig config)
    {
        if (config.getUsername() != null && config.getPassword() != null) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
            return credentialsProvider;
        }
        return null;
    }

    private static AWSCredentialsProvider getAwsCredentialsProvider(AwsSecurityConfig config)
    {
        if (config.getAccessKey().isPresent() && config.getSecretKey().isPresent()) {
            return new AWSStaticCredentialsProvider(new BasicAWSCredentials(
                    config.getAccessKey().get(),
                    config.getSecretKey().get()));
        }
        if (config.isUseInstanceCredentials()) {
            return InstanceProfileCredentialsProvider.getInstance();
        }
        return DefaultAWSCredentialsProviderChain.getInstance();
    }

    private static Optional<SSLContext> buildSslContext(
            Optional<File> keyStorePath,
            Optional<String> keyStorePassword,
            Optional<File> trustStorePath,
            Optional<String> trustStorePassword)
    {
        if (!keyStorePath.isPresent() && !trustStorePath.isPresent()) {
            return Optional.empty();
        }

        try {
            // load KeyStore if configured and get KeyManagers
            KeyStore keyStore = null;
            KeyManager[] keyManagers = null;
            if (keyStorePath.isPresent()) {
                char[] keyManagerPassword;
                try {
                    // attempt to read the key store as a PEM file
                    keyStore = PemReader.loadKeyStore(keyStorePath.get(), keyStorePath.get(), keyStorePassword);
                    // for PEM encoded keys, the password is used to decrypt the specific key (and does not protect the keystore itself)
                    keyManagerPassword = new char[0];
                }
                catch (IOException | GeneralSecurityException ignored) {
                    keyManagerPassword = keyStorePassword.map(String::toCharArray).orElse(null);

                    keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    try (InputStream in = new FileInputStream(keyStorePath.get())) {
                        keyStore.load(in, keyManagerPassword);
                    }
                }
                validateCertificates(keyStore);
                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyManagerPassword);
                keyManagers = keyManagerFactory.getKeyManagers();
            }

            // load TrustStore if configured, otherwise use KeyStore
            KeyStore trustStore = keyStore;
            if (trustStorePath.isPresent()) {
                trustStore = loadTrustStore(trustStorePath.get(), trustStorePassword);
            }

            // create TrustManagerFactory
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            // get X509TrustManager
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if ((trustManagers.length != 1) || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new RuntimeException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
            }
            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];

            // create SSLContext
            SSLContext result = SSLContext.getInstance("SSL");
            result.init(keyManagers, new TrustManager[] {trustManager}, null);
            return Optional.of(result);
        }
        catch (GeneralSecurityException | IOException e) {
            throw new PrestoException(ELASTICSEARCH_SSL_INITIALIZATION_FAILURE, e);
        }
    }

    private static KeyStore loadTrustStore(File trustStorePath, Optional<String> trustStorePassword)
            throws IOException, GeneralSecurityException
    {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            // attempt to read the trust store as a PEM file
            List<X509Certificate> certificateChain = PemReader.readCertificateChain(trustStorePath);
            if (!certificateChain.isEmpty()) {
                trustStore.load(null, null);
                for (X509Certificate certificate : certificateChain) {
                    X500Principal principal = certificate.getSubjectX500Principal();
                    trustStore.setCertificateEntry(principal.getName(), certificate);
                }
                return trustStore;
            }
        }
        catch (IOException | GeneralSecurityException ignored) {
        }

        try (InputStream in = new FileInputStream(trustStorePath)) {
            trustStore.load(in, trustStorePassword.map(String::toCharArray).orElse(null));
        }
        return trustStore;
    }

    private static void validateCertificates(KeyStore keyStore)
            throws GeneralSecurityException
    {
        for (String alias : list(keyStore.aliases())) {
            if (!keyStore.isKeyEntry(alias)) {
                continue;
            }
            Certificate certificate = keyStore.getCertificate(alias);
            if (!(certificate instanceof X509Certificate)) {
                continue;
            }

            try {
                ((X509Certificate) certificate).checkValidity();
            }
            catch (CertificateExpiredException e) {
                throw new CertificateExpiredException("KeyStore certificate is expired: " + e.getMessage());
            }
            catch (CertificateNotYetValidException e) {
                throw new CertificateNotYetValidException("KeyStore certificate is not yet valid: " + e.getMessage());
            }
        }
    }

    private Set<ElasticsearchNode> fetchNodes()
    {
        NodesResponse nodesResponse = doRequest("/_nodes/http", NODES_RESPONSE_CODEC::fromJson);

        ImmutableSet.Builder<ElasticsearchNode> result = ImmutableSet.builder();
        for (Map.Entry<String, NodesResponse.Node> entry : nodesResponse.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            NodesResponse.Node node = entry.getValue();

            if (node.getRoles().contains("data")) {
                result.add(new ElasticsearchNode(nodeId, node.getAddress()));
            }
        }

        return result.build();
    }

    public Set<ElasticsearchNode> getNodes()
    {
        return nodes.get();
    }

    public List<Shard> getSearchShards(String index)
    {
        Map<String, ElasticsearchNode> nodeById = getNodes().stream()
                .collect(toImmutableMap(ElasticsearchNode::getId, Function.identity()));

        SearchShardsResponse shardsResponse = doRequest(format("/%s/_search_shards", index), SEARCH_SHARDS_RESPONSE_CODEC::fromJson);

        ImmutableList.Builder<Shard> shards = ImmutableList.builder();
        List<ElasticsearchNode> nodes = ImmutableList.copyOf(nodeById.values());

        for (List<SearchShardsResponse.Shard> shardGroup : shardsResponse.getShardGroups()) {
            Stream<SearchShardsResponse.Shard> preferred = shardGroup.stream()
                    .sorted(this::shardPreference);

            Optional<SearchShardsResponse.Shard> candidate = preferred
                    .filter(shard -> shard.getNode() != null && nodeById.containsKey(shard.getNode()))
                    .findFirst();

            SearchShardsResponse.Shard chosen;
            ElasticsearchNode node;
            if (!candidate.isPresent()) {
                // pick an arbitrary shard with and assign to an arbitrary node
                chosen = preferred.findFirst().get();
                node = nodes.get(chosen.getShard() % nodes.size());
            }
            else {
                chosen = candidate.get();
                node = nodeById.get(chosen.getNode());
            }

            shards.add(new Shard(chosen.getShard(), node.getAddress()));
        }

        return shards.build();
    }

    private int shardPreference(SearchShardsResponse.Shard left, SearchShardsResponse.Shard right)
    {
        // Favor non-primary shards
        if (left.isPrimary() == right.isPrimary()) {
            return 0;
        }

        return left.isPrimary() ? 1 : -1;
    }

    public List<String> getIndexes()
    {
        return doRequest("/_cat/indices?h=index&format=json&s=index:asc", body -> {
            try {
                ImmutableList.Builder<String> result = ImmutableList.builder();
                JsonNode root = OBJECT_MAPPER.readTree(body);
                for (int i = 0; i < root.size(); i++) {
                    result.add(root.get(i).get("index").asText());
                }
                return result.build();
            }
            catch (IOException e) {
                throw new PrestoException(ELASTICSEARCH_INVALID_RESPONSE, e);
            }
        });
    }

    public IndexMetadata getIndexMetadata(String index)
    {
        String path = format("/%s/_mappings", index);

        return doRequest(path, body -> {
            try {
                JsonNode mappings = OBJECT_MAPPER.readTree(body)
                        .get(index)
                        .get("mappings");

                if (!mappings.has("properties")) {
                    // Older versions of ElasticSearch supported multiple "type" mappings
                    // for a given index. Newer versions support only one and don't
                    // expose it in the document. Here we skip it if it's present.
                    mappings = mappings.elements().next();
                }

                return new IndexMetadata(parseType(mappings.get("properties")));
            }
            catch (IOException e) {
                throw new PrestoException(ELASTICSEARCH_INVALID_RESPONSE, e);
            }
        });
    }

    private IndexMetadata.ObjectType parseType(JsonNode properties)
    {
        Iterator<Map.Entry<String, JsonNode>> entries = properties.fields();

        ImmutableList.Builder<IndexMetadata.Field> result = ImmutableList.builder();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> field = entries.next();

            String name = field.getKey();
            JsonNode value = field.getValue();
            if (value.has("type")) {
                String type = value.get("type").asText();

                if (type.equals("date")) {
                    List<String> formats = ImmutableList.of();
                    if (value.has("format")) {
                        formats = Arrays.asList(value.get("format").asText().split("\\|\\|"));
                    }
                    result.add(new IndexMetadata.Field(name, new IndexMetadata.DateTimeType(formats)));
                }
                else {
                    result.add(new IndexMetadata.Field(name, new IndexMetadata.PrimitiveType(type)));
                }
            }
            else if (value.has("properties")) {
                result.add(new IndexMetadata.Field(name, parseType(value.get("properties"))));
            }
        }

        return new IndexMetadata.ObjectType(result.build());
    }

    public SearchResponse beginSearch(String index, int shard, QueryBuilder query, Optional<List<String>> fields, List<String> documentFields)
    {
        SearchSourceBuilder sourceBuilder = SearchSourceBuilder.searchSource()
                .query(query)
                .size(scrollSize);

        fields.ifPresent(values -> {
            if (values.isEmpty()) {
                sourceBuilder.fetchSource(false);
            }
            else {
                sourceBuilder.fetchSource(values.toArray(new String[0]), null);
            }
        });
        documentFields.forEach(sourceBuilder::docValueField);

        SearchRequest request = new SearchRequest(index)
                .searchType(QUERY_THEN_FETCH)
                .preference("_shards:" + shard)
                .scroll(new TimeValue(scrollTimeout.toMillis()))
                .source(sourceBuilder);

        try {
            return client.search(request);
        }
        catch (IOException e) {
            throw new PrestoException(ELASTICSEARCH_CONNECTION_ERROR, e);
        }
        catch (ElasticsearchStatusException e) {
            Throwable[] suppressed = e.getSuppressed();
            if (suppressed.length > 0) {
                Throwable cause = suppressed[0];
                if (cause instanceof ResponseException) {
                    HttpEntity entity = ((ResponseException) cause).getResponse().getEntity();
                    try {
                        JsonNode reason = OBJECT_MAPPER.readTree(entity.getContent()).path("error")
                                .path("root_cause")
                                .path(0)
                                .path("reason");

                        if (!reason.isMissingNode()) {
                            throw new PrestoException(ELASTICSEARCH_QUERY_FAILURE, reason.asText(), e);
                        }
                    }
                    catch (IOException ex) {
                        e.addSuppressed(ex);
                    }
                }
            }

            throw new PrestoException(ELASTICSEARCH_CONNECTION_ERROR, e);
        }
    }

    public SearchResponse nextPage(String scrollId)
    {
        SearchScrollRequest request = new SearchScrollRequest(scrollId)
                .scroll(new TimeValue(scrollTimeout.toMillis()));

        try {
            return client.searchScroll(request);
        }
        catch (IOException e) {
            throw new PrestoException(ELASTICSEARCH_CONNECTION_ERROR, e);
        }
    }

    public void clearScroll(String scrollId)
    {
        ClearScrollRequest request = new ClearScrollRequest();
        request.addScrollId(scrollId);
        try {
            client.clearScroll(request);
        }
        catch (IOException e) {
            throw new PrestoException(ELASTICSEARCH_CONNECTION_ERROR, e);
        }
    }

    private <T> T doRequest(String path, ResponseHandler<T> handler)
    {
        checkArgument(path.startsWith("/"), "path must be an absolute path");

        Response response;
        try {
            response = client.getLowLevelClient()
                    .performRequest("GET", path);
        }
        catch (IOException e) {
            throw new PrestoException(ELASTICSEARCH_CONNECTION_ERROR, e);
        }

        String body;
        try {
            body = EntityUtils.toString(response.getEntity());
        }
        catch (IOException e) {
            throw new PrestoException(ELASTICSEARCH_INVALID_RESPONSE, e);
        }

        return handler.process(body);
    }

    private interface ResponseHandler<T>
    {
        T process(String body);
    }
}
