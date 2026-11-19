package com.ashaai.backend.controller;

import com.ashaai.backend.entity.Child;
import com.ashaai.backend.entity.HouseholdMember;
import com.ashaai.backend.entity.Referral;
import com.ashaai.backend.entity.Visit;
import com.ashaai.backend.repository.ChildRepository;
import com.ashaai.backend.repository.HouseholdMemberRepository;
import com.ashaai.backend.repository.ReferralRepository;
import com.ashaai.backend.repository.VisitRepository;
import com.ashaai.backend.security.AshaAuthenticationToken;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Read-Only FHIR R4 Interoperability Controller.
 * Built with standard Jackson JSON structures from existing domain entities.
 *
 * Guardrails:
 * 1. Restricted to authenticated supervisors (asha_heads) only.
 * 2. Strictly excludes Aadhaar, ABHA, and full address/phone numbers.
 * 3. Includes FHIR synthetic export notice in headers and meta.tag.
 * 4. Text-only codes with explicit TODO comments for LOINC / SNOMED validation prior to clinical production use.
 */
@RestController
@RequestMapping("/api/fhir")
public class FhirController {

    public static final String FHIR_NOTICE = "FHIR-ready export, synthetic data only";

    private final ChildRepository childRepository;
    private final HouseholdMemberRepository householdMemberRepository;
    private final VisitRepository visitRepository;
    private final ReferralRepository referralRepository;

    public FhirController(
            ChildRepository childRepository,
            HouseholdMemberRepository householdMemberRepository,
            VisitRepository visitRepository,
            ReferralRepository referralRepository
    ) {
        this.childRepository = childRepository;
        this.householdMemberRepository = householdMemberRepository;
        this.visitRepository = visitRepository;
        this.referralRepository = referralRepository;
    }

    private HttpHeaders createFhirHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FHIR-Notice", FHIR_NOTICE);
        headers.set(HttpHeaders.CONTENT_TYPE, "application/fhir+json;charset=UTF-8");
        return headers;
    }

    private ResponseEntity<?> checkSupervisorAuth(Authentication authentication) {
        if (!(authentication instanceof AshaAuthenticationToken auth) || auth.getAshaHeadId() == null) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Forbidden",
                    "message", "Supervisor access required",
                    "details", "Only supervisors (asha_heads) can export FHIR clinical interoperability data"
            ));
        }
        return null;
    }

    /**
     * FHIR R4 Patient Resource.
     * GET /api/fhir/Patient/{id}
     */
    @GetMapping("/Patient/{id}")
    public ResponseEntity<?> getPatient(@PathVariable UUID id, Authentication authentication) {
        ResponseEntity<?> authCheck = checkSupervisorAuth(authentication);
        if (authCheck != null) return authCheck;

        // Lookup either Child or HouseholdMember
        Optional<Child> childOpt = childRepository.findById(id);
        String patientName = null;
        String gender = "unknown";
        String birthDate = null;
        UUID patientId = id;

        if (childOpt.isPresent()) {
            Child child = childOpt.get();
            if (child.getHouseholdMember() != null) {
                patientName = child.getHouseholdMember().getName();
                if (child.getHouseholdMember().getGender() != null) {
                    gender = child.getHouseholdMember().getGender().equalsIgnoreCase("M") || child.getHouseholdMember().getGender().equalsIgnoreCase("male") ? "male" : "female";
                }
                if (child.getHouseholdMember().getDateOfBirth() != null) {
                    birthDate = child.getHouseholdMember().getDateOfBirth().toString();
                }
            } else {
                patientName = "Child";
            }
        } else {
            Optional<HouseholdMember> memberOpt = householdMemberRepository.findById(id);
            if (memberOpt.isPresent()) {
                HouseholdMember member = memberOpt.get();
                patientName = member.getName();
                if (member.getGender() != null) {
                    gender = member.getGender().equalsIgnoreCase("M") || member.getGender().equalsIgnoreCase("male") ? "male" : "female";
                }
                if (member.getDateOfBirth() != null) {
                    birthDate = member.getDateOfBirth().toString();
                }
            } else {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Not Found",
                        "message", "Patient resource not found",
                        "details", "No child or member found matching ID: " + id
                ));
            }
        }

        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("resourceType", "Patient");
        patient.put("id", patientId.toString());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("profile", List.of("http://hl7.org/fhir/StructureDefinition/Patient"));
        meta.put("tag", List.of(Map.of(
                "system", "https://ashaai.org/fhir/export-notice",
                "code", "synthetic-demo",
                "display", FHIR_NOTICE
        )));
        patient.put("meta", meta);

        patient.put("text", Map.of(
                "status", "generated",
                "div", "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + FHIR_NOTICE + "</div>"
        ));

        // Masked identifier - excludes Aadhaar / ABHA strictly
        patient.put("identifier", List.of(Map.of(
                "use", "usual",
                "type", Map.of(
                        "coding", List.of(Map.of(
                                "system", "http://terminology.hl7.org/CodeSystem/v2-0203",
                                "code", "MR",
                                "display", "Medical Record Number"
                        )),
                        "text", "AshaAI System Identifier (Aadhaar/ABHA excluded for privacy)"
                ),
                "value", patientId.toString()
        )));

        patient.put("active", true);
        patient.put("name", List.of(Map.of(
                "use", "official",
                "text", patientName != null ? patientName : "Anonymous Patient"
        )));
        patient.put("gender", gender);
        if (birthDate != null) {
            patient.put("birthDate", birthDate);
        }

        return ResponseEntity.ok().headers(createFhirHeaders()).body(patient);
    }

    /**
     * FHIR R4 Observation Searchset Bundle.
     * GET /api/fhir/Observation?patient={patientId}
     */
    @GetMapping("/Observation")
    public ResponseEntity<?> getObservations(
            @RequestParam("patient") UUID patientId,
            Authentication authentication
    ) {
        ResponseEntity<?> authCheck = checkSupervisorAuth(authentication);
        if (authCheck != null) return authCheck;

        List<Visit> visits = visitRepository.findByChild_IdOrderByVisitDateDesc(patientId);

        List<Map<String, Object>> entries = new ArrayList<>();

        for (Visit v : visits) {
            String visitDateStr = v.getVisitDate() != null ? v.getVisitDate().toString() : null;

            // 1. MUAC Observation
            if (v.getMuacMm() != null) {
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("resourceType", "Observation");
                obs.put("id", "muac-" + v.getId().toString());
                obs.put("status", "final");

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("tag", List.of(Map.of(
                        "system", "https://ashaai.org/fhir/export-notice",
                        "code", "synthetic-demo",
                        "display", FHIR_NOTICE
                )));
                obs.put("meta", meta);

                obs.put("category", List.of(Map.of(
                        "coding", List.of(Map.of(
                                "system", "http://terminology.hl7.org/CodeSystem/observation-category",
                                "code", "vital-signs",
                                "display", "Vital Signs"
                        ))
                )));

                // TODO: Verify LOINC code 56072-2 (Circumference Mid upper arm) before clinical production use
                obs.put("code", Map.of(
                        "text", "Mid-upper arm circumference (MUAC) - AI Screening Estimate / Field Tape"
                ));

                obs.put("subject", Map.of("reference", "Patient/" + patientId));
                if (visitDateStr != null) {
                    obs.put("effectiveDateTime", visitDateStr);
                }

                obs.put("valueQuantity", Map.of(
                        "value", v.getMuacMm(),
                        "unit", "mm",
                        "system", "http://unitsofmeasure.org",
                        "code", "mm"
                ));

                obs.put("note", List.of(Map.of(
                        "text", "AI-assisted screening estimate, not a diagnosis. Confirm with a clinician. " + FHIR_NOTICE
                )));

                entries.add(Map.of(
                        "fullUrl", "urn:uuid:" + obs.get("id"),
                        "resource", obs
                ));
            }

            // 2. Weight Observation
            if (v.getWeightKg() != null) {
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("resourceType", "Observation");
                obs.put("id", "weight-" + v.getId().toString());
                obs.put("status", "final");

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("tag", List.of(Map.of(
                        "system", "https://ashaai.org/fhir/export-notice",
                        "code", "synthetic-demo",
                        "display", FHIR_NOTICE
                )));
                obs.put("meta", meta);

                obs.put("category", List.of(Map.of(
                        "coding", List.of(Map.of(
                                "system", "http://terminology.hl7.org/CodeSystem/observation-category",
                                "code", "vital-signs",
                                "display", "Vital Signs"
                        ))
                )));

                // TODO: Verify LOINC code 29463-7 (Body weight) before clinical production use
                obs.put("code", Map.of(
                        "text", "Body weight"
                ));

                obs.put("subject", Map.of("reference", "Patient/" + patientId));
                if (visitDateStr != null) {
                    obs.put("effectiveDateTime", visitDateStr);
                }

                obs.put("valueQuantity", Map.of(
                        "value", v.getWeightKg(),
                        "unit", "kg",
                        "system", "http://unitsofmeasure.org",
                        "code", "kg"
                ));

                obs.put("note", List.of(Map.of("text", FHIR_NOTICE)));

                entries.add(Map.of(
                        "fullUrl", "urn:uuid:" + obs.get("id"),
                        "resource", obs
                ));
            }
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        bundle.put("total", entries.size());
        bundle.put("meta", Map.of("tag", List.of(Map.of(
                "system", "https://ashaai.org/fhir/export-notice",
                "code", "synthetic-demo",
                "display", FHIR_NOTICE
        ))));
        bundle.put("entry", entries);

        return ResponseEntity.ok().headers(createFhirHeaders()).body(bundle);
    }

    /**
     * FHIR R4 ServiceRequest Searchset Bundle.
     * GET /api/fhir/ServiceRequest?patient={patientId}
     */
    @GetMapping("/ServiceRequest")
    public ResponseEntity<?> getServiceRequests(
            @RequestParam("patient") UUID patientId,
            Authentication authentication
    ) {
        ResponseEntity<?> authCheck = checkSupervisorAuth(authentication);
        if (authCheck != null) return authCheck;

        List<Referral> referrals = referralRepository.findByChild_Id(patientId);
        if (referrals.isEmpty()) {
            referrals = referralRepository.findByHouseholdMember_Id(patientId);
        }

        List<Map<String, Object>> entries = new ArrayList<>();

        for (Referral r : referrals) {
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("resourceType", "ServiceRequest");
            sr.put("id", r.getId().toString());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tag", List.of(Map.of(
                    "system", "https://ashaai.org/fhir/export-notice",
                    "code", "synthetic-demo",
                    "display", FHIR_NOTICE
            )));
            sr.put("meta", meta);

            // Map referral status to FHIR status
            String fhirStatus = "active";
            if ("closed".equalsIgnoreCase(r.getStatus())) {
                fhirStatus = "completed";
            } else if ("rejected".equalsIgnoreCase(r.getStatus())) {
                fhirStatus = "revoked";
            }
            sr.put("status", fhirStatus);
            sr.put("intent", "order");

            sr.put("category", List.of(Map.of(
                    "text", "Nutrition Rehabilitation Centre (NRC) Nutritional Care Referral"
            )));

            // TODO: Verify SNOMED CT code 306206005 (Referral to nutrition service) before clinical production use
            sr.put("code", Map.of(
                    "text", r.getReason() != null ? r.getReason() : "Malnutrition Case Management"
            ));

            sr.put("subject", Map.of("reference", "Patient/" + patientId));
            if (r.getReferredDate() != null) {
                sr.put("authoredOn", r.getReferredDate().toString());
            }

            sr.put("note", List.of(Map.of(
                    "text", "Guardrail: No care loop closes without supervisor verification and recorded evidence. " + FHIR_NOTICE
            )));

            entries.add(Map.of(
                    "fullUrl", "urn:uuid:" + r.getId().toString(),
                    "resource", sr
            ));
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "searchset");
        bundle.put("total", entries.size());
        bundle.put("meta", Map.of("tag", List.of(Map.of(
                "system", "https://ashaai.org/fhir/export-notice",
                "code", "synthetic-demo",
                "display", FHIR_NOTICE
        ))));
        bundle.put("entry", entries);

        return ResponseEntity.ok().headers(createFhirHeaders()).body(bundle);
    }
}
