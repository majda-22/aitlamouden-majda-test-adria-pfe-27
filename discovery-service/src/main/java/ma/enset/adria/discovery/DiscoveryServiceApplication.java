package ma.enset.adria.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.cloud.netflix.eureka.server.EnableEurekaServer
public class DiscoveryServiceApplication {
    public static void main(String[] args) { SpringApplication.run(DiscoveryServiceApplication.class, args); }
}
