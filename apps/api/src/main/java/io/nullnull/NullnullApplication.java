package io.nullnull;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NullnullApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--nullnull.migration-only=true")) {
            try {
                MigrationRunner.run(System.getenv());
            } catch (RuntimeException failure) {
                // Driver errors can contain connection details; the container only exports a verdict.
                System.err.println("migration_result=failed");
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(NullnullApplication.class, args);
    }
}
