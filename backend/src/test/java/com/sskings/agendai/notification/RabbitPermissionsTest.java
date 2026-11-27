package com.sskings.agendai.notification;

import com.sskings.agendai.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O usuário do RabbitMQ de produção (deploy/rabbitmq/init-users.sh, o mesmo script do compose) num broker próprio: o
 * worker declara, publica e consome o que é do Agendaí, e nada além; não sobra outro usuário (nem o administrador
 * da 3a); trocar a senha invalida a antiga; senha fraca é recusada.
 */
class RabbitPermissionsTest {

    static final String PASSWORD = "worker-0123456789abcdef";

    static GenericContainer<?> broker;
    static CachingConnectionFactory worker;

    @BeforeAll
    static void start() throws Exception {
        broker = new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.RABBITMQ_IMAGE))
                .withCreateContainerCmdModifier(c -> c.withHostName("rabbitmq"))   // nó rabbit@rabbitmq, como no compose
                .withEnv("RABBITMQ_DEFAULT_USER", "agendai")                       // o administrador da 3a
                .withEnv("RABBITMQ_DEFAULT_PASS", "admin-da-3a-0123456789")
                .withExposedPorts(5672)
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/rabbitmq/init-users.sh")), "/init-users.sh")
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
        broker.start();
        Container.ExecResult r = init(PASSWORD);
        assertThat(r.getExitCode()).as(r.getStdout() + r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("usuário removido: agendai");
        worker = factory(PASSWORD);
        // o worker declara a topologia (como o RabbitAdmin dele faz ao conectar)
        Messaging m = new Messaging();
        RabbitAdmin admin = new RabbitAdmin(worker);
        for (Exchange e : new Exchange[]{m.events(), m.unroutedExchange()}) admin.declareExchange(e);
        for (Queue q : new Queue[]{m.unrouted(), m.emailQueue(), m.emailWait(Duration.ofMinutes(1)), m.emailDeadLetters()}) admin.declareQueue(q);
        for (Binding b : new Binding[]{m.unroutedBinding(m.unrouted(), m.unroutedExchange()), m.emailBinding(m.emailQueue(), m.events())}) {
            admin.declareBinding(b);
        }
    }

    @AfterAll
    static void stop() {
        worker.destroy();
        broker.stop();
    }

    static Container.ExecResult init(String password) throws Exception {
        return broker.execInContainerWithUser("rabbitmq", "sh", "-c", "RABBITMQ_WORKER_PASSWORD='" + password + "' sh /init-users.sh");
    }

    static List<String> users() throws Exception {
        return broker.execInContainerWithUser("rabbitmq", "rabbitmqctl", "-q", "list_users", "--no-table-headers")
                .getStdout().lines().map(l -> l.split("\t")[0]).sorted().toList();
    }

    static CachingConnectionFactory factory(String password) {
        CachingConnectionFactory f = new CachingConnectionFactory(broker.getHost(), broker.getMappedPort(5672));
        f.setUsername("agendai_worker");
        f.setPassword(password);
        f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return f;
    }

    /** Publica e espera a confirmação do broker, como o publicador do outbox e o worker fazem. */
    static void publish(CachingConnectionFactory f, String exchange, String key) {
        new RabbitTemplate(f).invoke(ops -> {
            ops.send(exchange, key, MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build());
            ops.waitForConfirmsOrDie(5000);
            return null;
        });
    }

    @Test
    @DisplayName("Worker: publica os eventos (e o sem destino vai para os não roteados), consome, usa a espera e a fila de falhas")
    void workerFlows() {
        RabbitTemplate rabbit = new RabbitTemplate(worker);
        publish(worker, Messaging.EXCHANGE, Messaging.EMAIL);
        assertThat(rabbit.receive(Messaging.EMAIL_QUEUE, 5000)).as("fila dos e-mails").isNotNull();
        publish(worker, Messaging.EXCHANGE, "tipo.sem.fila.v1");
        Message kept = rabbit.receive(Messaging.UNROUTED, 5000);
        assertThat(kept).as("exchange alternativo").isNotNull();
        publish(worker, "", Messaging.EMAIL_WAIT);
        assertThat(rabbit.receive(Messaging.EMAIL_WAIT, 5000)).as("fila de espera").isNotNull();
        publish(worker, "", Messaging.EMAIL_DLQ);
        assertThat(rabbit.receive(Messaging.EMAIL_DLQ, 5000)).as("fila de falhas").isNotNull();
    }

    @Test
    @DisplayName("Worker: nada fora do que é do Agendaí — não declara outra fila nem publica em outro exchange")
    void workerScoped() {
        RabbitAdmin admin = new RabbitAdmin(worker);
        assertThatThrownBy(() -> admin.declareQueue(QueueBuilder.durable("outra.fila").build())).hasStackTraceContaining("ACCESS_REFUSED");
        assertThatThrownBy(() -> publish(worker, "amq.topic", "x")).hasStackTraceContaining("ACCESS_REFUSED");
    }

    @Test
    @DisplayName("Rodar de novo troca a senha (a antiga deixa de valer) e apaga usuário criado à mão")
    void rotateAndPrune() throws Exception {
        broker.execInContainerWithUser("rabbitmq", "rabbitmqctl", "-q", "add_user", "depurar", "senha-qualquer-0123");
        String rotated = "worker-rotated-0123456789";
        Container.ExecResult r = init(rotated);
        CachingConnectionFactory old = factory(PASSWORD);
        CachingConnectionFactory fresh = factory(rotated);
        try {
            assertThat(r.getExitCode()).as(r.getStderr()).isZero();
            assertThat(r.getStdout()).contains("usuário removido: depurar");
            assertThatThrownBy(old::createConnection).isInstanceOf(AmqpException.class);
            publish(fresh, Messaging.EXCHANGE, Messaging.EMAIL);
            new RabbitTemplate(fresh).receive(Messaging.EMAIL_QUEUE, 5000);
        } finally {
            old.destroy();
            fresh.destroy();
            init(PASSWORD);
        }
        assertThat(users()).containsExactly("agendai_worker");
        assertThat(worker.createConnection().isOpen()).as("a senha original de volta").isTrue();
    }

    @Test
    @DisplayName("Senha: 16 caracteres passa, 15 não; vazia ou com caractere que exigiria escape no JSON é recusada")
    void passwordRules() throws Exception {
        for (String bad : new String[]{"a".repeat(15), "", "aspas\"0123456789abcdef", "espaco 0123456789abcdef", "cifrao$0123456789abcdef",
                "barra\\0123456789abcdef"}) {
            Container.ExecResult r = init(bad.replace("'", ""));
            assertThat(r.getExitCode()).as(bad).isEqualTo(1);
            assertThat(r.getStderr()).as(bad).contains("RABBITMQ_WORKER_PASSWORD");
        }
        assertThat(init("b".repeat(16)).getExitCode()).isZero();
        assertThat(init(PASSWORD).getExitCode()).isZero();
        assertThat(users()).containsExactly("agendai_worker");
    }
}
