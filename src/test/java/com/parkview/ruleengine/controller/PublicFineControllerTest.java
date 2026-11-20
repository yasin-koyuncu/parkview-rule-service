package com.parkview.ruleengine.controller;

import com.parkview.platform.web.ResourceNotFoundException;
import com.parkview.platform.web.TooManyRequestsException;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.dto.response.PublicFineResponse;
import com.parkview.ruleengine.service.fine.PublicFineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public fine page (ADR 0020): no token, the proof is in the request, one answer for "no such fine" and "wrong plate". */
@WebMvcTest(PublicFineController.class)
@PlatformWebSlice
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class PublicFineControllerTest {

    private static final UUID FINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PATH = "/api/v1/public/fines/{fineId}/lookup";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PublicFineService publicFines;

    /** The access policy of the web slice needs it; this controller never calls it. */
    @MockitoBean
    ZoneDirectory zoneDirectory;

    private static PublicFineResponse answer() {
        return new PublicFineResponse(FINE_ID, Instant.parse("2026-10-09T10:00:00Z"), new BigDecimal("450"), "SEK", "unpaid", "Storgatan 1",
                "Storgatan 1", "A1", "overstay", "stayed longer than allowed", "The stay limit is 60 minutes.",
                Instant.parse("2026-10-09T09:45:00Z"), 5, Instant.parse("2026-10-09T09:50:00Z"), null);
    }

    private org.springframework.test.web.servlet.ResultActions lookup(String body) throws Exception {
        return mvc.perform(post(PATH, FINE_ID).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void withoutAnyTokenTheRightPlateGetsTheFineAndTheBrowserIsToldToForgetIt() throws Exception {
        when(publicFines.lookup(eq(FINE_ID), eq("abc 123"), any())).thenReturn(answer());

        lookup("{\"plate\":\"abc 123\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.fineId").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.status").value("unpaid"))
                .andExpect(jsonPath("$.amount").value(450))
                .andExpect(jsonPath("$.currency").value("SEK"))
                .andExpect(jsonPath("$.reasonText").value("stayed longer than allowed"))
                .andExpect(jsonPath("$.siteAddress").value("Storgatan 1"));
    }

    @Test
    void theAnswerHasNoPicturesNoDetectionIdsNoPlateNoKeeperAndNoInternalIds() throws Exception {
        when(publicFines.lookup(eq(FINE_ID), any(), any())).thenReturn(answer());

        String json = lookup("{\"plate\":\"ABC123\"}").andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(json)
                .doesNotContain("imagePaths", "detectionIds", "plate", "ABC123", "keeper", "owner", "userId", "zoneId", "siteId", "cameraId", "snapshot");
    }

    @Test
    void anUnknownFineAndAWrongPlateGiveTheSameProblem() throws Exception {
        when(publicFines.lookup(eq(FINE_ID), eq("WRONG1"), any())).thenThrow(new ResourceNotFoundException("Fine", FINE_ID));

        lookup("{\"plate\":\"WRONG1\"}")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("notFound.fine"));
    }

    @Test
    void aLockedFineNumberAnswers429WithRetryAfter() throws Exception {
        when(publicFines.lookup(eq(FINE_ID), any(), any())).thenThrow(
                TooManyRequestsException.coded("publicLookup.locked", "Too many attempts for this fine. Try again later.", Duration.ofSeconds(600)));

        lookup("{\"plate\":\"ABC123\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("publicLookup.locked"));
    }

    @Test
    void aPlateWithCharactersNoPlateHasIsRefusedBeforeAnyLookup() throws Exception {
        for (String bad : new String[] {"{\"plate\":\"AB<script>\"}", "{\"plate\":\"\"}", "{}", "{\"plate\":\"ABCDEFGHIJKL\"}"}) {
            lookup(bad).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(publicFines);
    }

    @Test
    void thePlateIsInTheBodyOnlyAGetWithItInTheUrlIsNotAnEndpoint() throws Exception {
        mvc.perform(get("/api/v1/public/fines/{fineId}/lookup?plate=ABC123", FINE_ID)).andExpect(status().is4xxClientError());
        verifyNoInteractions(publicFines);
    }

    @Test
    void theAddressOfTheCallerIsTheFirstOneOfXForwardedFor() throws Exception {
        when(publicFines.lookup(eq(FINE_ID), any(), any())).thenReturn(answer());

        mvc.perform(post(PATH, FINE_ID).header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"plate\":\"ABC123\"}")).andExpect(status().isOk());

        verify(publicFines).lookup(FINE_ID, "ABC123", "203.0.113.7");
    }

    @Test
    void otherFineEndpointsStayAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/public/fines/{fineId}/other", FINE_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
