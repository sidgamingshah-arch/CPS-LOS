package com.helix.risk.client;

import com.helix.common.web.ApiException;
import com.helix.risk.dto.CreditInputsDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches the credit-inputs snapshot (terms + confirmed spread) from origination-service. */
@Component
public class OriginationClient {

    private final RestClient client;

    public OriginationClient(@Value("${helix.origination-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public CreditInputsDto creditInputs(String applicationReference) {
        try {
            CreditInputsDto inputs = client.get()
                    .uri("/api/applications/{ref}/credit-inputs", applicationReference)
                    .retrieve()
                    .body(CreditInputsDto.class);
            if (inputs == null) {
                throw ApiException.notFound("No credit inputs for " + applicationReference);
            }
            return inputs;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "origination-service unavailable: " + e.getMessage());
        }
    }
}
