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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FhirControllerTest {

    @Mock
    private ChildRepository childRepository;

    @Mock
    private HouseholdMemberRepository householdMemberRepository;

    @Mock
    private VisitRepository visitRepository;

    @Mock
    private ReferralRepository referralRepository;

    private FhirController fhirController;

    private final UUID supervisorHeadId = UUID.randomUUID();
    private final UUID ashaId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fhirController = new FhirController(childRepository, householdMemberRepository, visitRepository, referralRepository);
    }

    @Test
    @DisplayName("Non-supervisor ASHA calling /api/fhir/Patient/{id} returns 403 Forbidden")
    void ashaCallingFhirPatient_returns403() {
        AshaAuthenticationToken ashaToken = new AshaAuthenticationToken("worker", ashaId, null, UUID.randomUUID(), Collections.emptyList());

        ResponseEntity<?> response = fhirController.getPatient(patientId, ashaToken);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Forbidden", body.get("error"));
    }

    @Test
    @DisplayName("Supervisor calling /api/fhir/Patient/{id} returns 200 with FHIR Patient and synthetic notice")
    void supervisorCallingFhirPatient_returnsPatient() {
        AshaAuthenticationToken supervisorToken = new AshaAuthenticationToken("supervisor", ashaId, supervisorHeadId, UUID.randomUUID(), Collections.emptyList());

        HouseholdMember member = new HouseholdMember();
        member.setName("Aarav Shinde");
        member.setGender("male");
        member.setDateOfBirth(LocalDate.of(2023, 5, 10));

        Child child = new Child();
        child.setId(patientId);
        child.setHouseholdMember(member);

        when(childRepository.findById(patientId)).thenReturn(Optional.of(child));

        ResponseEntity<?> response = fhirController.getPatient(patientId, supervisorToken);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("FHIR-ready export, synthetic data only", response.getHeaders().getFirst("X-FHIR-Notice"));

        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Patient", body.get("resourceType"));
        assertEquals(patientId.toString(), body.get("id"));
        assertEquals("male", body.get("gender"));
        assertEquals("2023-05-10", body.get("birthDate"));

        // Verify Aadhaar / ABHA excluded
        assertNull(body.get("aadhaar"));
        assertNull(body.get("abha"));
    }

    @Test
    @DisplayName("Supervisor calling /api/fhir/Observation returns 200 with vital signs observations bundle")
    void supervisorCallingFhirObservation_returnsBundle() {
        AshaAuthenticationToken supervisorToken = new AshaAuthenticationToken("supervisor", ashaId, supervisorHeadId, UUID.randomUUID(), Collections.emptyList());

        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitDate(LocalDate.now());
        visit.setMuacMm(BigDecimal.valueOf(114.5));
        visit.setWeightKg(BigDecimal.valueOf(8.2));

        when(visitRepository.findByChild_IdOrderByVisitDateDesc(patientId)).thenReturn(List.of(visit));

        ResponseEntity<?> response = fhirController.getObservations(patientId, supervisorToken);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("FHIR-ready export, synthetic data only", response.getHeaders().getFirst("X-FHIR-Notice"));

        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Bundle", body.get("resourceType"));
        assertEquals("searchset", body.get("type"));
        assertEquals(2, body.get("total")); // 1 MUAC + 1 Weight
    }

    @Test
    @DisplayName("Supervisor calling /api/fhir/ServiceRequest returns 200 with referral service requests bundle")
    void supervisorCallingFhirServiceRequest_returnsBundle() {
        AshaAuthenticationToken supervisorToken = new AshaAuthenticationToken("supervisor", ashaId, supervisorHeadId, UUID.randomUUID(), Collections.emptyList());

        Referral referral = new Referral();
        referral.setId(UUID.randomUUID());
        referral.setStatus("pending");
        referral.setReason("Severe Acute Malnutrition (SAM)");
        referral.setReferredDate(OffsetDateTime.now());

        when(referralRepository.findByChild_Id(patientId)).thenReturn(List.of(referral));

        ResponseEntity<?> response = fhirController.getServiceRequests(patientId, supervisorToken);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Bundle", body.get("resourceType"));
        assertEquals(1, body.get("total"));
    }
}
