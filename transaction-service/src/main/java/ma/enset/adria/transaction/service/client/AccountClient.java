package ma.enset.adria.transaction.service.client;

import ma.enset.adria.transaction.service.dto.AccountSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "account-service", url = "${bank.account-service.url}")
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountSnapshot getAccount(@PathVariable("id") UUID id);
}
