package com.parkview.ruleengine.dto.request;

import com.parkview.platform.domain.validation.PlateNumber;
import jakarta.validation.constraints.NotBlank;

/** The proof on the public fine page (ADR 0020): the plate, typed as printed (spaces, dashes and case do not matter). */
public record PublicFineLookupRequest(@NotBlank @PlateNumber(message = "{validation.plateCharacters}") String plate) {
}
