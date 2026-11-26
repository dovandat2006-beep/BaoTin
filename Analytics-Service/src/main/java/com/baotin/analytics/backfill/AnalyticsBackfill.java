package com.baotin.analytics.backfill;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Manual operator CLI only; never exposed as a web endpoint. */
public final class AnalyticsBackfill {

    static final String FAIL_DIAGNOSTIC =
            "ANALYTICS_BACKFILL FAIL input_role_state_or_database_check; no secret/payload diagnostics emitted";

    private static final long MAX_PASSWORD_FILE_BYTES = 4096L;
    private static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Set<String> ALLOWED_USERS = Set.of("baotin_analytics", "baotin_analytics_migrator");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AnalyticsBackfill() {
    }

    public static void main(String[] args) {
        int exitCode = runCli(args, System.getenv(), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runCli(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        SingleConnectionDataSource dataSource = null;
        try {
            OperatorConfig config = validateOperatorConfig(env);
            dataSource = new SingleConnectionDataSource(config.url(), config.user(), config.password(), true);
            BackfillRunSummary summary = executeWithDataSource(dataSource, args, env);
            out.println(JSON.writeValueAsString(summary));
            return 0;
        } catch (Exception ignored) {
            err.println(FAIL_DIAGNOSTIC);
            return 1;
        } finally {
            if (dataSource != null) {
                dataSource.destroy();
            }
        }
    }

    static OperatorConfig validateOperatorConfig(Map<String, String> env) throws IOException {
        if (env == null) {
            throw new IllegalArgumentException("Environment required");
        }
        String url = env.get("ANALYTICS_BACKFILL_URL");
        String user = env.get("ANALYTICS_BACKFILL_USER");
        String passwordPathRaw = env.get("ANALYTICS_BACKFILL_PASSWORD_FILE");
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL required");
        }
        if (user == null || !ALLOWED_USERS.contains(user)) {
            throw new IllegalArgumentException("Owning analytics operator role required");
        }
        if (passwordPathRaw == null || passwordPathRaw.isBlank()) {
            throw new IllegalArgumentException("Password file required");
        }
        Path passwordFile = Path.of(passwordPathRaw);
        if (Files.isSymbolicLink(passwordFile)
                || !Files.isRegularFile(passwordFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(passwordFile)
                || Files.size(passwordFile) > MAX_PASSWORD_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid password file");
        }
        String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
        return new OperatorConfig(url, user, password);
    }

    static BackfillRunSummary executeWithDataSource(
            DataSource dataSource,
            String[] args,
            Map<String, String> env) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("Subcommand required");
        }

        BackfillService service = new BackfillService(new JdbcTemplate(dataSource));
        return switch (args[0]) {
            case "file" -> {
                require(args.length == 2 || args.length == 3);
                int batchSize = args.length == 3 ? parseBatchSize(args[2]) : BackfillService.DEFAULT_BATCH_SIZE;
                yield service.startFileRun(Path.of(args[1]), batchSize);
            }
            case "resume-file" -> {
                require(args.length == 3 || args.length == 4);
                UUID runId = UUID.fromString(args[1]);
                Integer batchSizeOverride = args.length == 4 ? parseBatchSize(args[3]) : null;
                yield service.resumeFileRun(runId, Path.of(args[2]), batchSizeOverride);
            }
            case "replay" -> {
                require(args.length >= 2 && args.length <= 4);
                Properties props = consumerProps(args[1], env);
                int batchSize = args.length >= 3 ? parseBatchSize(args[2]) : BackfillService.DEFAULT_BATCH_SIZE;
                List<String> topics = args.length == 4 ? parseTopics(args[3]) : List.of();
                yield service.startKafkaReplayRun(props, topics, batchSize, DEFAULT_POLL_TIMEOUT);
            }
            case "resume-replay" -> {
                require(args.length == 3 || args.length == 4);
                UUID runId = UUID.fromString(args[1]);
                Properties props = consumerProps(args[2], env);
                int batchSize = args.length == 4 ? parseBatchSize(args[3]) : service.status(runId).batchSize();
                yield service.resumeKafkaReplayRun(runId, props, batchSize, DEFAULT_POLL_TIMEOUT);
            }
            case "status" -> {
                require(args.length == 2);
                yield service.status(UUID.fromString(args[1]));
            }
            default -> throw new IllegalArgumentException("Unknown subcommand");
        };
    }

    private static Properties consumerProps(String bootstrapServers, Map<String, String> env) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers required");
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.trim());
        if (env != null) {
            putIfNonBlank(props, env, "KAFKA_SECURITY_PROTOCOL", CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
            putIfNonBlank(props, env, "KAFKA_SASL_MECHANISM", "sasl.mechanism");
            putIfNonBlank(props, env, "KAFKA_SASL_JAAS_CONFIG", "sasl.jaas.config");
        }
        return props;
    }

    private static void putIfNonBlank(Properties props, Map<String, String> env, String envKey, String propKey) {
        String value = env.get(envKey);
        if (value != null && !value.isBlank()) {
            props.put(propKey, value.trim());
        }
    }

    private static List<String> parseTopics(String rawTopics) {
        if (rawTopics == null || rawTopics.isBlank()) {
            throw new IllegalArgumentException("topics must not be blank");
        }
        Set<String> topics = new LinkedHashSet<>();
        for (String segment : rawTopics.split(",", -1)) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("topics must not contain blank entries");
            }
            topics.add(trimmed);
        }
        return new ArrayList<>(topics);
    }

    private static int parseBatchSize(String raw) {
        try {
            int batchSize = Integer.parseInt(raw);
            if (batchSize < BackfillService.MIN_BATCH_SIZE || batchSize > BackfillService.MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("batchSize out of range");
            }
            return batchSize;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid batchSize", exception);
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid arguments");
        }
    }

    record OperatorConfig(String url, String user, String password) {
        @Override
        public String toString() {
            return "OperatorConfig[url=" + url + ", user=" + user + ", password=***]";
        }
    }
}
