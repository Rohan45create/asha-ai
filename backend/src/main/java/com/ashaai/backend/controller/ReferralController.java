package com.ashaai.backend.controller;

import com.ashaai.backend.entity.Asha;
import com.ashaai.backend.entity.Child;
import com.ashaai.backend.entity.EditHistory;
import com.ashaai.backend.entity.HouseholdMember;
import com.ashaai.backend.entity.PendingReview;
import com.ashaai.backend.entity.Referral;
import com.ashaai.backend.repository.AshaRepository;
import com.ashaai.backend.repository.ChildRepository;
import com.ashaai.backend.repository.EditHistoryRepository;
import com.ashaai.backend.repository.HouseholdMemberRepository;
import com.ashaai.backend.repository.PendingReviewRepository;
import com.ashaai.backend.repository.ReferralRepository;
import com.ashaai.backend.security.AshaAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/referrals")
public class ReferralController {

    private final ReferralRepository referralRepository;
    private final ChildRepository childRepository;
    private final AshaRepository ashaRepository;
    private final HouseholdMemberRepository householdMemberRepository;
    private final EditHistoryRepository editHistoryRepository;
    private final PendingReviewRepository pendingReviewRepository;

    public ReferralController(ReferralRepository referralRepository,
                              ChildRepository childRepository,
                              AshaRepository ashaRepository,
                              HouseholdMemberRepository householdMemberRepository,
                              EditHistoryRepository editHistoryRepository,
                              PendingReviewRepository pendingReviewRepository) {
        this.referralRepository = referralRepository;
        this.childRepository = childRepository;
        this.ashaRepository = ashaRepository;
        this.householdMemberRepository = householdMemberRepository;
        this.editHistoryRepository = editHistoryRepository;
        this.pendingReviewRepository = pendingReviewRepository;
    }

    @GetMapping
    public ResponseEntity<List<Referral>> getAll(Authentication authentication) {
        if (!(authentication instanceof AshaAuthenticationToken auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (auth.getAshaHeadId() != null) {
            return ResponseEntity.ok(referralRepository.findByAsha_Head_Id(auth.getAshaHeadId()));
        } else if (auth.getAshaId() != null) {
            return ResponseEntity.ok(referralRepository.findByAsha_Id(auth.getAshaId()));
        }
        return ResponseEntity.ok(referralRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Referral> getById(@PathVariable UUID id) {
        return referralRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Referral entity, Authentication authentication) {
        if (!(authentication instanceof AshaAuthenticationToken auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Authentication required",
                    "details", "Valid authentication token is required"
            ));
        }

        // Resolve child if provided
        Child child = null;
        if (entity.getChild() != null && entity.getChild().getId() != null) {
            child = childRepository.findById(entity.getChild().getId()).orElse(null);
            entity.setChild(child);
        }

        // Resolve household member
        HouseholdMember member = entity.getHouseholdMember();
        if (member != null && member.getId() != null) {
            member = householdMemberRepository.findById(member.getId()).orElse(member);
            entity.setHouseholdMember(member);
        } else if (child != null && child.getHouseholdMember() != null) {
            entity.setHouseholdMember(child.getHouseholdMember());
        }

        // Resolve Asha
        Asha asha = entity.getAsha();
        if (asha == null || asha.getId() == null) {
            if (auth.getAshaId() != null) {
                asha = ashaRepository.findById(auth.getAshaId()).orElse(null);
            } else if (child != null && child.getAsha() != null) {
                asha = child.getAsha();
            }
        } else {
            asha = ashaRepository.findById(asha.getId()).orElse(asha);
        }
        entity.setAsha(asha);

        if (entity.getAsha() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "asha_id is required",
                    "details", "Could not resolve ASHA from token or payload"
            ));
        }
        if (entity.getChild() == null && entity.getHouseholdMember() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "Either child_id or household_member_id is required",
                    "details", "Referral subject must be specified"
            ));
        }

        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("pending");
        } else {
            entity.setStatus(entity.getStatus().toLowerCase());
        }
        if (entity.getReferredDate() == null) {
            entity.setReferredDate(OffsetDateTime.now());
        }

        Referral saved = referralRepository.save(entity);

        // Keep children.nrc_referral_status in sync
        if (child != null) {
            child.setNrcReferralStatus(saved.getStatus());
            childRepository.save(child);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Referral updateData, Authentication authentication) {
        Referral referral = referralRepository.findById(id).orElse(null);
        if (referral == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NOT_FOUND",
                    "message", "Referral not found",
                    "details", "No referral exists with ID: " + id
            ));
        }

        if (updateData.getStatus() != null && !updateData.getStatus().isBlank()) {
            String newStatus = updateData.getStatus().toLowerCase();
            if ("closed".equals(newStatus)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "FORBIDDEN",
                        "message", "Referrals cannot be closed directly via generic update",
                        "details", "Supervisor verification and clinical evidence note are required. Use POST /api/referrals/" + id + "/close"
                ));
            }
            referral.setStatus(newStatus);
            if ("admitted".equals(newStatus) && referral.getAdmittedDate() == null) {
                referral.setAdmittedDate(LocalDate.now());
            } else if ("discharged".equals(newStatus) && referral.getDischargedDate() == null) {
                referral.setDischargedDate(LocalDate.now());
            }
        }

        if (updateData.getNrcName() != null) {
            referral.setNrcName(updateData.getNrcName());
        }
        if (updateData.getAdmittedDate() != null) {
            referral.setAdmittedDate(updateData.getAdmittedDate());
        }
        if (updateData.getDischargedDate() != null) {
            referral.setDischargedDate(updateData.getDischargedDate());
        }
        if (updateData.getFollowUpDueDate() != null) {
            referral.setFollowUpDueDate(updateData.getFollowUpDueDate());
        }

        Referral saved = referralRepository.save(referral);

        // Keep children.nrc_referral_status in sync
        Child child = referral.getChild();
        if (child != null) {
            child.setNrcReferralStatus(saved.getStatus());
            childRepository.save(child);
        }

        return ResponseEntity.ok(saved);
    }

    /**
     * Request referral closure (ASHA or Supervisor).
     * Transitions referral to 'awaiting_verification' and creates a PendingReview entry for the supervisor.
     */
    @PostMapping("/{id}/request-closure")
    @Transactional
    public ResponseEntity<?> requestClosure(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        if (!(authentication instanceof AshaAuthenticationToken auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Authentication required",
                    "details", "Valid authentication token is required"
            ));
        }

        Referral referral = referralRepository.findById(id).orElse(null);
        if (referral == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NOT_FOUND",
                    "message", "Referral not found",
                    "details", "No referral exists with ID: " + id
            ));
        }

        if ("closed".equalsIgnoreCase(referral.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "CONFLICT",
                    "message", "Referral is already closed",
                    "details", "Cannot request closure for an already closed referral"
            ));
        }

        referral.setStatus("awaiting_verification");
        Referral saved = referralRepository.save(referral);

        if (referral.getChild() != null) {
            Child child = referral.getChild();
            child.setNrcReferralStatus("awaiting_verification");
            childRepository.save(child);
        }

        String notes = (body != null && body.get("notes") != null && !body.get("notes").isBlank())
                ? body.get("notes")
                : "Closure requested by ASHA; awaiting supervisor clinical verification";

        PendingReview review = new PendingReview();
        review.setTableName("referrals");
        review.setRecordId(referral.getId());
        review.setReason(notes);
        review.setFlaggedBy(auth.getAuthUserId() != null ? auth.getAuthUserId() : auth.getAshaId());
        review.setStatus("PENDING");
        pendingReviewRepository.save(review);

        return ResponseEntity.ok(Map.of(
                "message", "Closure requested successfully. Awaiting supervisor verification.",
                "referral", saved
        ));
    }

    /**
     * Verify and complete referral closure (Supervisor only).
     * Requirements:
     * - ASHA calling close endpoint: 403
     * - Supervisor without evidence (under 10 chars): 422
     * - Already closed: 409
     * - Success: status -> 'closed', EditHistory recorded with supervisor as editor and reason in same transaction.
     */
    @PostMapping("/{id}/close")
    @Transactional
    public ResponseEntity<?> verifyAndClose(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        if (!(authentication instanceof AshaAuthenticationToken auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Authentication required",
                    "details", "Valid authentication token is required"
            ));
        }

        // ASHA calling the close endpoint: 403
        if (auth.getAshaHeadId() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "FORBIDDEN",
                    "message", "Only supervisors (asha_heads) can close referrals",
                    "details", "ASHA workers cannot directly close referrals without supervisor verification"
            ));
        }

        Referral referral = referralRepository.findById(id).orElse(null);
        if (referral == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NOT_FOUND",
                    "message", "Referral not found",
                    "details", "No referral exists with ID: " + id
            ));
        }

        // Already closed: 409
        if ("closed".equalsIgnoreCase(referral.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "CONFLICT",
                    "message", "Referral is already closed",
                    "details", "This referral has already been verified and closed"
            ));
        }

        // Supervisor without evidence (under 10 characters): 422
        String evidence = body != null ? body.get("evidence") : null;
        if (evidence == null || evidence.trim().length() < 10) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "UNPROCESSABLE_ENTITY",
                    "message", "Evidence note is required and must be at least 10 characters long",
                    "details", "Provided evidence was missing or shorter than 10 characters"
            ));
        }

        String oldStatus = referral.getStatus();
        referral.setStatus("closed");
        if (referral.getDischargedDate() == null) {
            referral.setDischargedDate(LocalDate.now());
        }
        Referral saved = referralRepository.save(referral);

        if (referral.getChild() != null) {
            Child child = referral.getChild();
            child.setNrcReferralStatus("closed");
            childRepository.save(child);
        }

        // Complete any open PendingReview for this referral
        List<PendingReview> pendingReviews = pendingReviewRepository.findByRecordId(id);
        for (PendingReview pr : pendingReviews) {
            if ("PENDING".equalsIgnoreCase(pr.getStatus()) && "referrals".equalsIgnoreCase(pr.getTableName())) {
                pr.setStatus("CONFIRMED");
                pendingReviewRepository.save(pr);
            }
        }

        // Record EditHistory: evidence note as reason, supervisor as editor, source = 'supervisor_verified' in reason
        EditHistory history = new EditHistory();
        history.setTableName("referrals");
        history.setRecordId(id);
        history.setFieldName("status");
        history.setOldValue(oldStatus != null ? oldStatus : "unknown");
        history.setNewValue("closed");
        history.setEditedBy(auth.getAshaHeadId());
        history.setReason("[supervisor_verified] " + evidence.trim());
        editHistoryRepository.save(history);

        return ResponseEntity.ok(Map.of(
                "message", "Referral successfully verified and closed",
                "referral", saved
        ));
    }
}
