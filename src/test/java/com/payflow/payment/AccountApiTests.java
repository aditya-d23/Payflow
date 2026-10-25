package com.payflow.payment;

import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @AfterEach
    void clearDatabase() {
        accountRepository.deleteAll();
    }

    @Test
    void createsAnAccountWithZeroBalance() throws Exception {
        create("""
                {"accountNumber":"PF30001","currency":"USD"}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/PF30001"))
                .andExpect(jsonPath("$.accountNumber").value("PF30001"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void ignoresAClientSuppliedOpeningBalance() throws Exception {
        create("""
                {"accountNumber":"PF30001","currency":"INR","balance":1000000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void rejectsDuplicateAccountNumbers() throws Exception {
        String body = """
                {"accountNumber":"PF30001","currency":"INR"}
                """;
        create(body).andExpect(status().isCreated());
        create(body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void rejectsInvalidInputWithFieldErrors() throws Exception {
        create("""
                {"accountNumber":"x","currency":"inr"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void rejectsUnsupportedCurrencies() throws Exception {
        create("""
                {"accountNumber":"PF30001","currency":"JPY"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void returnsAccountAndBalance() throws Exception {
        accountRepository.save(new Account("PF30001", new BigDecimal("12.50"), "INR"));

        mockMvc.perform(get("/api/v1/accounts/PF30001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("PF30001"));
        mockMvc.perform(get("/api/v1/accounts/PF30001/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(12.5))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/NOPE1/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void closingRequiresAZeroBalanceAndIsTerminal() throws Exception {
        accountRepository.save(new Account("PF30001", new BigDecimal("1"), "INR"));
        accountRepository.save(new Account("PF30002", BigDecimal.ZERO, "INR"));

        changeStatus("PF30001", "CLOSED")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));

        changeStatus("PF30002", "CLOSED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        changeStatus("PF30002", "ACTIVE")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void rejectsUnknownStatusValues() throws Exception {
        accountRepository.save(new Account("PF30001", BigDecimal.ZERO, "INR"));
        changeStatus("PF30001", "DELETED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions changeStatus(String accountNumber, String status)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/accounts/" + accountNumber + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }
}
