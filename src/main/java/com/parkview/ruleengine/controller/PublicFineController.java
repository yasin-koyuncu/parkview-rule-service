package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.dto.request.PublicFineLookupRequest;
import com.parkview.ruleengine.dto.response.PublicFineResponse;
import com.parkview.ruleengine.service.fine.PublicFineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The public fine page (ADR 0020). This is the one handler of this service that needs no token and has no {@code @PreAuthorize} on purpose: it is
 * listed in {@code parkview.security.public-paths} and in the gateway matrix as PUBLIC, and the proof is the fine number and the plate in the
 * request, checked by {@link PublicFineService}. It answers with the explanation of ADR 0005 and the status, nothing else.
 */
@RestController
@RequestMapping("/api/v1/public/fines")
@RequiredArgsConstructor
@Tag(name = "Public fine page")
public class PublicFineController {

    private final PublicFineService publicFines;

    @PostMapping("/{fineId}/lookup")
    @Operation(summary = "Read a fine with its number and the plate, without signing in",
            description = "The plate goes in the body, never in the URL. An unknown number and a wrong plate give the same 404; after five wrong plates "
                    + "for one number within 15 minutes the number answers 429 with Retry-After. The answer is the plain-language explanation of the fine "
                    + "and its status: no pictures, no detection ids, no keeper, no internal ids.")
    @ApiResponse(responseCode = "200", description = "The fine")
    @ApiResponse(responseCode = "400", description = "The plate has characters a plate cannot have")
    @ApiResponse(responseCode = "404", description = "Unknown fine number, or not the plate of this fine (the same answer)")
    @ApiResponse(responseCode = "429", description = "Too many wrong plates for this fine number; see Retry-After")
    public ResponseEntity<PublicFineResponse> lookup(@PathVariable UUID fineId, @Valid @RequestBody PublicFineLookupRequest request,
                                                     HttpServletRequest http) {
        PublicFineResponse body = publicFines.lookup(fineId, request.plate(), clientAddress(http));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    /** The first address of {@code X-Forwarded-For} (set by the gateway), else the peer. Only hashed and stored, never trusted for a decision. */
    private static String clientAddress(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
