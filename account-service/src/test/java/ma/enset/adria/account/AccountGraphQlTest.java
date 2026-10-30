package ma.enset.adria.account;

import ma.enset.adria.account.repository.AccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:account-graphql-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AccountGraphQlTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AccountRepository repository;
    @BeforeEach void reset() { repository.deleteAll(); }
    static final String CREATE = "mutation($input: CreateAccountInput!) { createAccount(input: $input) { id customerReference currency status balance } }";

    ResultActions graph(String query, Map<String, Object> variables) throws Exception {
        var result = mvc.perform(post("/graphql").contentType("application/json").accept("application/json")
                .content(json.writeValueAsString(Map.of("query", query, "variables", variables))));
        var initial = result.andReturn();
        if (initial.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(initial));
        return result.andExpect(status().isOk());
    }
    Map<String, Object> input() {
        return new HashMap<>(Map.of("customerReference", "GRAPHQL-CLIENT", "currency", "MAD", "balance", "1500.123456789012345678"));
    }
    String create() throws Exception {
        var response = graph(CREATE, Map.of("input", input())).andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createAccount.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createAccount.balance").value("1500.123456789012345678"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.data.createAccount.id");
    }

    @Test void createsAndReadsThroughSameServiceAsRestWithoutLosingDecimalPrecision() throws Exception {
        String id = create();
        assertThat(UUID.fromString(id)).isNotNull();
        graph("query($id: ID!) { account(id: $id) { id currency status balance } }", Map.of("id", id))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.account.id").value(id))
                .andExpect(jsonPath("$.data.account.balance").value("1500.123456789012345678"));
        mvc.perform(get("/api/accounts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test void updatesStatusUsingExistingService() throws Exception {
        String id = create();
        graph("mutation($id: ID!) { updateAccountStatus(id: $id, status: BLOCKED) { id status } }", Map.of("id", id))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.updateAccountStatus.status").value("BLOCKED"));
        mvc.perform(get("/api/accounts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test void paginationMatchesRestDefaultsAndExplicitPages() throws Exception {
        for (int i = 0; i < 3; i++) create();
        graph("{ accounts { page size totalElements totalPages content { id } } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.accounts.page").value(0)).andExpect(jsonPath("$.data.accounts.size").value(20))
                .andExpect(jsonPath("$.data.accounts.totalElements").value(3));
        graph("{ accounts(page: 0, size: 2) { totalPages content { id } } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.accounts.totalPages").value(2))
                .andExpect(jsonPath("$.data.accounts.content.length()").value(2));
        graph("{ accounts(page: 1, size: 2) { content { id } } }", Map.of())
                .andExpect(jsonPath("$.data.accounts.content.length()").value(1));
    }

    @Test void missingAccountIsBusinessErrorInsideHttp200Response() throws Exception {
        graph("query($id: ID!) { account(id: $id) { id } }", Map.of("id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.data.account").isEmpty())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].path[0]").value("account"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "blank-reference", "currency", "numeric-decimal", "exponent", "comma"})
    void invalidInputHasStructuredErrorsAndDoesNotPersist(String invalid) throws Exception {
        var input = input();
        switch (invalid) {
            case "negative" -> input.put("balance", "-1");
            case "blank-reference" -> input.put("customerReference", " ");
            case "currency" -> input.put("currency", "mad");
            case "numeric-decimal" -> input.put("balance", 1500.25);
            case "exponent" -> input.put("balance", "1e3");
            case "comma" -> input.put("balance", "1500,25");
        }
        graph(CREATE, Map.of("input", input)).andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
        assertThat(repository.count()).isZero();
    }

    @Test void rejectsInvalidUuidPaginationAndEnum() throws Exception {
        graph("query($id: ID!) { account(id: $id) { id } }", Map.of("id", "invalid-uuid"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
        graph("{ accounts(page: -1, size: 101) { totalElements } }", Map.of())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
        graph("mutation { updateAccountStatus(id: \"" + UUID.randomUUID() + "\", status: CLOSED) { id } }", Map.of())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
    }

    @Test void acceptsStringLiteralDecimalsAndNeverExposesInternalMovementMutations() throws Exception {
        graph("mutation { createAccount(input: {customerReference: \"LITERAL\", currency: \"MAD\", balance: \"0.000000000000000001\"}) { balance } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createAccount.balance").value("0.000000000000000001"));
        graph("{ __schema { mutationType { fields { name } } } }", Map.of())
                .andExpect(jsonPath("$.data.__schema.mutationType.fields[*].name").value(containsInAnyOrder("createAccount", "updateAccountStatus")));
    }
}
