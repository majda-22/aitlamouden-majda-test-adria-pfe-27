package ma.enset.adria.account;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.ConstraintViolationException;
import ma.enset.adria.account.domain.AccountStatus;
import ma.enset.adria.account.repository.AccountRepository;
import ma.enset.adria.account.service.AccountService;
import ma.enset.adria.account.service.dto.CreateAccountRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounts-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private AccountService service;

    @BeforeEach
    void clearAccounts() {
        repository.deleteAll();
    }

    @Test
    void createsAnActiveAccountWithGeneratedIdAndLocation() throws Exception {
        String id = createAccount("1250.123456789");
        UUID uuid = UUID.fromString(id);

        var account = repository.findById(uuid).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo("1250.123456789");
        mvc.perform(get("/api/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.customerReference").value("CLIENT-001"))
                .andExpect(jsonPath("$.currency").value("MAD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void acceptsZeroBalance() throws Exception {
        createAccount("0");
        assertThat(repository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"customerReference\":\" \",\"currency\":\"MAD\",\"balance\":0}",
            "{\"customerReference\":null,\"currency\":\"MAD\",\"balance\":0}",
            "{\"customerReference\":\"C1\",\"currency\":null,\"balance\":0}",
            "{\"customerReference\":\"C1\",\"currency\":\"mad\",\"balance\":0}",
            "{\"customerReference\":\"C1\",\"currency\":\"MA\",\"balance\":0}",
            "{\"customerReference\":\"C1\",\"currency\":\"M4D\",\"balance\":0}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":-0.01}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":null}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":0.0000000000000000001}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":100000000000000000000}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":\"abc\"}",
            "{\"customerReference\":\"C1\",\"currency\":\"MAD\",\"balance\":0,\"status\":\"BLOCKED\"}",
            "{"
    })
    void rejectsInvalidCreationWithoutPersisting(String body) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        assertThat(repository.count()).isZero();
    }

    @Test
    void validatesRequestsEvenWhenServiceIsCalledDirectly() {
        assertThatThrownBy(() -> service.create(new CreateAccountRequest("", "mad", new BigDecimal("-1"))))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsTooLongCustomerReference() throws Exception {
        String body = "{\"customerReference\":\"" + "C".repeat(256) + "\",\"currency\":\"MAD\",\"balance\":0}";
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void returns404WhenAccountDoesNotExist() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/api/accounts/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(patch("/api/accounts/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void changesStatusInBothDirectionsWithoutChangingBalance() throws Exception {
        String id = createAccount("100.50");
        for (String newStatus : new String[]{"BLOCKED", "ACTIVE", "ACTIVE"}) {
            mvc.perform(patch("/api/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + newStatus + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(newStatus));
            var persisted = repository.findById(UUID.fromString(id)).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(AccountStatus.valueOf(newStatus));
            assertThat(persisted.getBalance()).isEqualByComparingTo("100.50");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"status\":null}", "{\"status\":\"CLOSED\"}", "{\"status\":\"active\"}",
            "{\"status\":0}", "{\"status\":\"BLOCKED\",\"balance\":999}"
    })
    void rejectsInvalidStatusChanges(String body) throws Exception {
        String id = createAccount("100.50");
        mvc.perform(patch("/api/accounts/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        var account = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isEqualByComparingTo("100.50");
    }

    @Test
    void listsAccountsWithPaginationAndSupportsEmptyPages() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.size").value(20));
        for (int i = 0; i < 3; i++) {
            createAccount("0");
        }
        mvc.perform(get("/api/accounts?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/accounts?page=1&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/accounts?page=2&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "size=abc"})
    void rejectsInvalidPagination(String parameters) throws Exception {
        mvc.perform(get("/api/accounts?" + parameters))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedUuid() throws Exception {
        mvc.perform(get("/api/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private String createAccount(String balance) throws Exception {
        String json = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerReference\":\"CLIENT-001\",\"currency\":\"MAD\",\"balance\":" + balance + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");
        assertThat(UUID.fromString(id)).isNotNull();
        return id;
    }
}
