package ma.enset.adria.transaction.service.client;

import ma.enset.adria.transaction.service.dto.DebitRequest;
import ma.enset.adria.transaction.service.dto.DebitResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "account-service", url = "${bank.account-service.url}")
public interface AccountClient {

    @PostMapping("/internal/debits")
    DebitResponse debit(@RequestBody DebitRequest request);

    @PostMapping("/internal/transfers")
    ma.enset.adria.transaction.service.dto.TransferResult transfer(
            @RequestBody ma.enset.adria.transaction.service.dto.TransferRequest request);
}
