package ma.enset.adria.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.cloud.config.server.EnableConfigServer
public class ConfigServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ConfigServiceApplication.class, args); }
}
