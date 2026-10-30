package ma.enset.adria.transaction;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.ConstraintViolationException;
import ma.enset.adria.transaction.repository.TransactionRepository;
import ma.enset.adria.transaction.service.TransactionService;
import ma.enset.adria.transaction.service.dto.CreateTransactionRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transactions-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bank.transaction.recovery.enabled=false",
        "spring.cloud.openfeign.client.config.account-service.readTimeout=200"
})
@AutoConfigureMockMvc
class TransactionApiTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final AccountStub ACCOUNT_SERVER = new AccountStub();

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("bank.account-service.url", ACCOUNT_SERVER::url);
    }

    @AfterAll
    static void stopAccountServer() {
        ACCOUNT_SERVER.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private TransactionService service;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        ACCOUNT_SERVER.reset();
    }

    @Test
    void createsAndRetrievesCompletedTransactionAfterDebit() throws Exception {
        Instant before = Instant.now();
        String id = create(ACCOUNT_ID, "250.123456789");
        var transaction = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(transaction.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(transaction.getAmount()).isEqualByComparingTo("250.123456789");
        assertThat(transaction.getCreatedAt()).isBetween(before, Instant.now());
        mvc.perform(get("/api/transactions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.merchantCategory").value("GROCERIES"))
                .andExpect(jsonPath("$.country").value("MA"));
        assertThat(ACCOUNT_SERVER.requests()).containsExactly(
                new AccountStub.Request("POST", "/internal/debits"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null", "\"abc\"", "0.0000000000000000001"})
    void rejectsInvalidAmountBeforeCallingAccountService(String amount) throws Exception {
        submit(body(ACCOUNT_ID, amount, "MAD"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{", "{\"accountId\":\"not-a-uuid\"}",
            "{\"accountId\":null,\"amount\":1,\"currency\":\"MAD\",\"merchantCategory\":\"SHOP\",\"country\":\"MA\"}"
    })
    void rejectsMalformedOrMissingFields(String body) throws Exception {
        submit(body).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mad", "MA", "M4D", ""})
    void rejectsInvalidCurrencyFormat(String currency) throws Exception {
        submit(body(ACCOUNT_ID, "1", currency)).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @Test
    void rejectsBlankMerchantCategoryAndCountry() throws Exception {
        submit(body(ACCOUNT_ID, "1", "MAD").replace("GROCERIES", " "))
                .andExpect(status().isBadRequest());
        submit(body(ACCOUNT_ID, "1", "MAD").replace("\"country\":\"MA\"", "\"country\":\" \""))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @Test
    void rejectsClientSuppliedCreationTime() throws Exception {
        String body = body(ACCOUNT_ID, "1", "MAD");
        submit(body.substring(0, body.length() - 1) + ",\"createdAt\":\"2000-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void validatesDirectServiceCalls() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID().toString(), new CreateTransactionRequest(ACCOUNT_ID, BigDecimal.ZERO,
                "MAD", "SHOP", "MA"))).isInstanceOf(ConstraintViolationException.class);
        assertThat(repository.count()).isZero();
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @Test
    void rejectsAmountAboveConfiguredCeilingBeforeCallingAccountService() throws Exception {
        reject("1000.01", 400, "MAX_AMOUNT_EXCEEDED");
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @Test
    void acceptsExactCeilingAndExactBalance() throws Exception {
        ACCOUNT_SERVER.respond(200, "ACTIVE", "MAD", "1000.00");
        create(ACCOUNT_ID, "1000.00");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void distinguishesMissingAccountFromUnavailableService() throws Exception {
        ACCOUNT_SERVER.respond(404, "ACTIVE", "MAD", "2000");
        reject("250", 404, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectsBlockedAccount() throws Exception {
        ACCOUNT_SERVER.respond(200, "BLOCKED", "MAD", "2000");
        reject("250", 400, "ACCOUNT_BLOCKED");
    }

    @Test
    void rejectsCurrencyMismatch() throws Exception {
        ACCOUNT_SERVER.respond(200, "ACTIVE", "EUR", "2000");
        reject("250", 400, "CURRENCY_MISMATCH");
    }

    @Test
    void rejectsInsufficientBalance() throws Exception {
        ACCOUNT_SERVER.respond(200, "ACTIVE", "MAD", "249.99");
        reject("250", 400, "INSUFFICIENT_BALANCE");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void keepsPendingOnAccountServiceFailures(int status) throws Exception {
        ACCOUNT_SERVER.respond(status, "ACTIVE", "MAD", "2000");
        pending("250");
    }

    @Test
    void keepsPendingWhenDebitResponseTimesOut() throws Exception {
        ACCOUNT_SERVER.delay(1200);
        pending("250");
        assertThat(ACCOUNT_SERVER.requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"id\":\"not-a-uuid\"}"})
    void rejectsInvalidAccountServiceResponsesWithoutInventingAnAccount(String response) throws Exception {
        ACCOUNT_SERVER.rawResponse(response);
        pending("250");
    }

    @Test
    void listsOnlyRequestedAccountWithPaginationWithoutCallingAccountService() throws Exception {
        create(ACCOUNT_ID, "1");
        create(ACCOUNT_ID, "2");
        create(UUID.randomUUID(), "3");
        ACCOUNT_SERVER.reset();
        mvc.perform(get("/api/transactions").param("accountId", ACCOUNT_ID.toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(2))
                .andExpect(jsonPath("$.content[0].accountId").value(ACCOUNT_ID.toString()));
        mvc.perform(get("/api/transactions").param("accountId", ACCOUNT_ID.toString())
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(1));
        mvc.perform(get("/api/transactions").param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        assertThat(ACCOUNT_SERVER.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "size=abc"})
    void rejectsInvalidPagination(String query) throws Exception {
        mvc.perform(get("/api/transactions?accountId=" + ACCOUNT_ID + "&" + query))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresValidAccountFilterAndDistinguishesMissingTransaction() throws Exception {
        mvc.perform(get("/api/transactions")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions?accountId=invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions/invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/transactions").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void reject(String amount, int status, String code) throws Exception {
        submit(body(ACCOUNT_ID, amount, "MAD"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
        if (code.equals("MAX_AMOUNT_EXCEEDED")) assertThat(repository.count()).isZero();
        else {
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findAll().get(0).getStatus().name()).isEqualTo("REJECTED");
        }
    }

    private void pending(String amount) throws Exception {
        submit(body(ACCOUNT_ID, amount, "MAD")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().get(0).getStatus().name()).isEqualTo("PENDING");
    }

    private String create(UUID accountId, String amount) throws Exception {
        String response = submit(body(accountId, amount, "MAD"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String body(UUID accountId, String amount, String currency) {
        return "{\"accountId\":\"" + accountId + "\",\"amount\":" + amount + ",\"currency\":\"" + currency
                + "\",\"merchantCategory\":\"GROCERIES\",\"country\":\"MA\"}";
    }
}
