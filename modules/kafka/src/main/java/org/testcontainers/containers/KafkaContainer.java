package org.testcontainers.containers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.core.ContainerDef;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.ComparableVersion;
import org.testcontainers.utility.DockerImageName;

import java.util.Objects;

/**
 * This container wraps Confluent Kafka and Zookeeper (optionally)
 */
public class KafkaContainer extends GenericContainer<KafkaContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("confluentinc/cp-kafka");

    private static final String DEFAULT_TAG = "5.4.3";

    public static final int KAFKA_PORT = 9093;

    public static final int ZOOKEEPER_PORT = 2181;

    private static final String DEFAULT_INTERNAL_TOPIC_RF = "1";

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

    // https://docs.confluent.io/platform/7.0.0/release-notes/index.html#ak-raft-kraft
    private static final String MIN_KRAFT_TAG = "7.0.0";

    public static final String DEFAULT_CLUSTER_ID = "4L6g3nShT-eMCtK--X86sw";

    protected String externalZookeeperConnect = null;

    private boolean kraftEnabled = false;

    private String clusterId = DEFAULT_CLUSTER_ID;

    /**
     * @deprecated use {@link KafkaContainer(DockerImageName)} instead
     */
    @Deprecated
    public KafkaContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    /**
     * @deprecated use {@link KafkaContainer(DockerImageName)} instead
     */
    @Deprecated
    public KafkaContainer(String confluentPlatformVersion) {
        this(DEFAULT_IMAGE_NAME.withTag(confluentPlatformVersion));
    }

    static KafkaContainer from(String image) {
        return with(KafkaServiceContainer.from(image));
    }

    static KafkaContainer from(DockerImageName image) {
        return with(KafkaServiceContainer.from(image));
    }

    static KafkaContainer with(KafkaServiceContainer serviceContainer) {
        ContainerDef containerDef = serviceContainer.getContainerDef();
        KafkaContainer container = new KafkaContainer(containerDef.getImage());
        container.withServiceContainer(serviceContainer);
        return container;
    }

    private KafkaContainer(RemoteDockerImage image) {
        super(image);
        withServiceContainer(KafkaServiceContainer.from(image.get()));
        withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint("sh");
        });
    }

    private KafkaServiceContainer kafkaServiceContainer;

    private void withServiceContainer(KafkaServiceContainer kafkaServiceContainer) {
        this.kafkaServiceContainer = kafkaServiceContainer;
        setContainerDef(kafkaServiceContainer.getContainerDef());
    }

    public KafkaContainer(final DockerImageName dockerImageName) {
        this(new RemoteDockerImage(dockerImageName));
    }

    public KafkaContainer withEmbeddedZookeeper() {
        if (this.kafkaServiceContainer.isRaftMode()) {
            throw new IllegalStateException("Cannot configure Zookeeper when using Kraft mode");
        }
        this.kafkaServiceContainer.withExternalZookeeperConnect(null);
        return self();
    }

    public KafkaContainer withExternalZookeeper(String connectString) {
        if (this.kafkaServiceContainer.isRaftMode()) {
            throw new IllegalStateException("Cannot configure Zookeeper when using Kraft mode");
        }
        this.kafkaServiceContainer.withExternalZookeeperConnect(connectString);
        return self();
    }

    public KafkaContainer withKraft() {
        if (this.externalZookeeperConnect != null) {
            throw new IllegalStateException("Cannot configure Kraft mode when Zookeeper configured");
        }
        verifyMinKraftVersion();
        this.kraftEnabled = true;
        return self();
    }

    private void verifyMinKraftVersion() {
        String actualVersion = DockerImageName.parse(getDockerImageName()).getVersionPart();
        if (new ComparableVersion(actualVersion).isLessThan(MIN_KRAFT_TAG)) {
            throw new IllegalArgumentException(
                String.format(
                    "Provided Confluent Platform's version %s is not supported in Kraft mode (must be %s or above)",
                    actualVersion,
                    MIN_KRAFT_TAG
                )
            );
        }
    }

    private boolean isLessThanCP740() {
        String actualVersion = DockerImageName.parse(getDockerImageName()).getVersionPart();
        return new ComparableVersion(actualVersion).isLessThan("7.4.0");
    }

    public KafkaContainer withClusterId(String clusterId) {
        Objects.requireNonNull(clusterId, "clusterId cannot be null");
        this.clusterId = clusterId;
        return self();
    }

    public String getBootstrapServers() {
        return String.format("PLAINTEXT://%s:%s", getHost(), getMappedPort(KAFKA_PORT));
    }

    @Override
    protected void configure() {
        if (this.kafkaServiceContainer.isRaftMode()) {
            waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1));
            configureKraft();
        } else {
            waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1));
            configureZookeeper();
        }
    }

    protected void configureKraft() {
        //CP 7.4.0
        getEnvMap().computeIfAbsent("CLUSTER_ID", key -> clusterId);
        getEnvMap().computeIfAbsent("KAFKA_NODE_ID", key -> getEnvMap().get("KAFKA_BROKER_ID"));
        withEnv(
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
            String.format("%s,CONTROLLER:PLAINTEXT", getEnvMap().get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP"))
        );
        withEnv("KAFKA_LISTENERS", String.format("%s,CONTROLLER://0.0.0.0:9094", getEnvMap().get("KAFKA_LISTENERS")));

        withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
        getEnvMap()
            .computeIfAbsent(
                "KAFKA_CONTROLLER_QUORUM_VOTERS",
                key -> {
                    return String.format(
                        "%s@%s:9094",
                        getEnvMap().get("KAFKA_NODE_ID"),
                        getNetwork() != null ? getNetworkAliases().get(0) : "localhost"
                    );
                }
            );
        withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
    }

    protected void configureZookeeper() {
        String externalZookeeperConnect = this.kafkaServiceContainer.getExternalZookeeperConnect();
        if (externalZookeeperConnect != null) {
            withEnv("KAFKA_ZOOKEEPER_CONNECT", externalZookeeperConnect);
        } else {
            addExposedPort(ZOOKEEPER_PORT);
            withEnv("KAFKA_ZOOKEEPER_CONNECT", "localhost:" + ZOOKEEPER_PORT);
        }
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        super.containerIsStarting(containerInfo);

        String command = "#!/bin/bash\n";
        // exporting KAFKA_ADVERTISED_LISTENERS with the container hostname
        command +=
            String.format(
                "export KAFKA_ADVERTISED_LISTENERS=%s,%s\n",
                getBootstrapServers(),
                brokerAdvertisedListener(containerInfo)
            );

        if (this.kafkaServiceContainer.isRaftMode() && isLessThanCP740()) {
            // Optimization: skip the checks
            command += "echo '' > /etc/confluent/docker/ensure \n";
            command += commandKraft();
        }

        if (!this.kafkaServiceContainer.isRaftMode()) {
            // Optimization: skip the checks
            command += "echo '' > /etc/confluent/docker/ensure \n";
            command += commandZookeeper();
        }

        // Run the original command
        command += "/etc/confluent/docker/run \n";
        copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
    }

    protected String commandKraft() {
        String command = "sed -i '/KAFKA_ZOOKEEPER_CONNECT/d' /etc/confluent/docker/configure\n";
        command +=
            "echo 'kafka-storage format --ignore-formatted -t \"" +
            this.clusterId +
            "\" -c /etc/kafka/kafka.properties' >> /etc/confluent/docker/configure\n";
        return command;
    }

    protected String commandZookeeper() {
        String command = "echo 'clientPort=" + ZOOKEEPER_PORT + "' > zookeeper.properties\n";
        command += "echo 'dataDir=/var/lib/zookeeper/data' >> zookeeper.properties\n";
        command += "echo 'dataLogDir=/var/lib/zookeeper/log' >> zookeeper.properties\n";
        command += "zookeeper-server-start zookeeper.properties &\n";
        return command;
    }

    protected String brokerAdvertisedListener(InspectContainerResponse containerInfo) {
        return String.format("BROKER://%s:%s", containerInfo.getConfig().getHostName(), "9092");
    }
}
