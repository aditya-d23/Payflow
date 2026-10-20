package com.payflow.payment;

import com.payflow.payment.dto.CreateAccountRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountValidationTests {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void rejectsBlankAccountNumberAndInvalidCurrency() {
        CreateAccountRequest request = new CreateAccountRequest("", "inr");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("accountNumber", "accountNumber", "currency");
    }
}
