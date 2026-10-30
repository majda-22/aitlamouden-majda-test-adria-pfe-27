package ma.enset.adria.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import ma.enset.adria.transaction.service.client.AccountClient;

@SpringBootApplication
@EnableFeignClients(clients = AccountClient.class)
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
