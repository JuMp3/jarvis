package dev.jarvis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JarvisApplication {

    private static String[] arguments = new String[0];
    private static volatile ConfigurableApplicationContext context;

    static void main(String[] args) {
        arguments = args;
        context = SpringApplication.run(JarvisApplication.class, args);
    }

    /**
     * Applies new settings: closes the application and starts it again in the same process, so the saved
     * settings are read like at startup. Open pages reconnect by themselves.
     */
    public static void restart() {
        Thread restarter = new Thread(() -> {
            ConfigurableApplicationContext current = context;
            if (current != null) {
                current.close();
            }
            context = SpringApplication.run(JarvisApplication.class, arguments);
        }, "jarvis-restart");
        restarter.setDaemon(false); // keeps the JVM alive between the old and the new context
        restarter.start();
    }
}
