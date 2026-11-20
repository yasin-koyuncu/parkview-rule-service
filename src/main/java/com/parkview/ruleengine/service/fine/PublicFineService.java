package com.parkview.ruleengine.service.fine;

import com.parkview.platform.security.Plates;
import com.parkview.platform.web.ResourceNotFoundException;
import com.parkview.platform.web.TooManyRequestsException;
import com.parkview.ruleengine.config.PublicLookupProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.PublicFineLookup;
import com.parkview.ruleengine.dto.response.PublicFineResponse;
import com.parkview.ruleengine.mapper.EvidenceMapper;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.PublicFineLookupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The public fine page (ADR 0020): a fine is read with its number and the plate, without signing in.
 *
 * <p>An unknown fine number and a wrong plate are the same answer ({@link ResourceNotFoundException}, code {@code notFound.fine}), so the page
 * cannot tell which numbers exist or which plate belongs to a fine. Wrong plates are counted per fine number within the window, for numbers that
 * do not exist as well; the next try after the limit is a 429 whatever the number. Every look-up is recorded in its own transaction (this class is
 * not transactional, the rows must survive the exception that answers the request). The plate that was typed is never stored or logged.
 */
@Service
@RequiredArgsConstructor
public class PublicFineService {

    private final FineRepository fines;
    private final FineEvidenceService evidence;
    private final PublicFineLookupRepository lookups;
    private final PublicLookupProperties properties;
    private final Clock clock;

    /**
     * @param typedPlate    the plate as typed; compared after normalisation
     * @param clientAddress the address of the caller, only stored as a keyed hash; may be null
     * @throws ResourceNotFoundException the number is unknown or the plate is not the one of the fine
     * @throws TooManyRequestsException  the fine number took too many wrong plates in the window
     */
    public PublicFineResponse lookup(UUID fineId, String typedPlate, String clientAddress) {
        Instant now = clock.instant();
        String clientHash = hash(clientAddress);

        List<Instant> failures = lookups.failuresSince(fineId, now.minus(properties.window()));
        if (failures.size() >= properties.maxFailures()) {
            lookups.save(PublicFineLookup.of(fineId, now, PublicFineLookup.LOCKED, clientHash));
            Duration wait = Duration.between(now, failures.get(failures.size() - properties.maxFailures()).plus(properties.window()));
            throw TooManyRequestsException.coded("publicLookup.locked", "Too many attempts for this fine. Try again later.", wait);
        }

        Optional<Fine> fine = fines.findById(fineId).filter(f -> samePlate(f.getPlate(), typedPlate));
        if (fine.isEmpty()) {
            lookups.save(PublicFineLookup.of(fineId, now, PublicFineLookup.NOT_FOUND, clientHash));
            throw new ResourceNotFoundException("Fine", fineId);
        }
        lookups.save(PublicFineLookup.of(fineId, now, PublicFineLookup.OK, clientHash));
        return EvidenceMapper.toPublic(evidence.explain(fine.get()));
    }

    /** Equal after normalisation, compared in constant time (the plate is the second factor). */
    private static boolean samePlate(String stored, String typed) {
        String a = Plates.normalizeOrNull(stored);
        String b = Plates.normalizeOrNull(typed);
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA-256 of the address with {@code hash-salt} as key (plain SHA-256 while no salt is set), first 16 bytes in hex; null for no address. */
    private String hash(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            byte[] bytes;
            String salt = properties.hashSalt();
            if (salt == null || salt.isEmpty()) {
                bytes = MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            } else {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                bytes = mac.doFinal(address.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
