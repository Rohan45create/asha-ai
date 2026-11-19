package com.ashaai.backend.controller;

import com.ashaai.backend.entity.Asha;
import com.ashaai.backend.entity.AshaHead;
import com.ashaai.backend.entity.Child;
import com.ashaai.backend.entity.NgoAppointment;
import com.ashaai.backend.entity.Referral;
import com.ashaai.backend.entity.Visit;
import com.ashaai.backend.repository.EditHistoryRepository;
import com.ashaai.backend.repository.NgoAppointmentRepository;
import com.ashaai.backend.repository.ReferralRepository;
import com.ashaai.backend.repository.VisitRepository;
import com.ashaai.backend.security.AshaAuthenticationToken;
import com.ashaai.backend.service.AdminService;
import com.ashaai.backend.service.RiskEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CareLoopControllerTest {

    @Mock
    private RiskEngineService riskEngineService;

    @Mock
    private AdminService adminService;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private NgoAppointmentRepository ngoAppointmentRepository;

    @Mock
    private VisitRepository visitRepository;

    @Mock
    private EditHistoryRepository editHistoryRepository;

    private AdminController adminController;

    private final UUID supervisorHeadId = UUID.randomUUID();
    private final UUID ashaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adminController = new AdminController(riskEngineService, adminService);
    }

    @Test
    @DisplayName("ASHA (non-supervisor) calling /api/admin/care-loop returns 403 Forbidden")
    void ashaCallingCareLoop_returnsForbidden() {
        // ASHA token with no ashaHeadId
        AshaAuthenticationToken ashaToken = new AshaAuthenticationToken("worker-principal", ashaId, null, UUID.randomUUID(), Collections.emptyList());

        ResponseEntity<?> response = adminController.getCareLoop(false, ashaToken);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Forbidden", body.get("error"));
        assertEquals("Supervisor access required", body.get("message"));
    }

    @Test
    @DisplayName("Supervisor calling /api/admin/care-loop returns 200 with care loop cases")
    void supervisorCallingCareLoop_returns200WithCases() {
        AshaAuthenticationToken supervisorToken = new AshaAuthenticationToken("supervisor-principal", ashaId, supervisorHeadId, UUID.randomUUID(), Collections.emptyList());

        Map<String, Object> mockCase = Map.of(
                "id", UUID.randomUUID().toString(),
                "status", "pending",
                "daysSinceFlagged", 10L,
                "ruleBasedDropOffRisk", "HIGH",
                "reasons", List.of("no appointment booked after 7 days")
        );
        when(adminService.getCareLoopCases(supervisorHeadId, false)).thenReturn(List.of(mockCase));

        ResponseEntity<?> response = adminController.getCareLoop(false, supervisorToken);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof List);
        List<?> cases = (List<?>) response.getBody();
        assertEquals(1, cases.size());
        assertEquals("HIGH", ((Map<?, ?>) cases.get(0)).get("ruleBasedDropOffRisk"));
    }

    @Test
    @DisplayName("Care loop deterministic rules: Flagged > 7 days with no appointment triggers Rule 1 and HIGH risk")
    void careLoopLogic_flaggedOver7DaysNoAppt_highRisk() {
        AshaHead head = new AshaHead();
        head.setId(supervisorHeadId);

        Asha asha = new Asha();
        asha.setId(ashaId);
        asha.setName("Sunita Tai");
        asha.setHead(head);

        Referral referral = new Referral();
        referral.setId(UUID.randomUUID());
        referral.setAsha(asha);
        referral.setStatus("pending");
        referral.setReferredDate(OffsetDateTime.now().minusDays(10)); // 10 days ago

        when(referralRepository.findByAsha_Head_Id(supervisorHeadId)).thenReturn(List.of(referral));
        when(ngoAppointmentRepository.findByReferral_IdOrderByScheduledAtDesc(referral.getId())).thenReturn(Collections.emptyList());
        when(editHistoryRepository.findByRecordIdOrderByEditedAtDesc(referral.getId())).thenReturn(Collections.emptyList());

        AdminService realAdminService = new AdminService(
                null, null, null, null, null,
                referralRepository, null, null, null, null, null, null,
                visitRepository, null, null, null, null,
                ngoAppointmentRepository, editHistoryRepository
        );

        List<Map<String, Object>> cases = realAdminService.getCareLoopCases(supervisorHeadId, false);

        assertEquals(1, cases.size());
        Map<String, Object> c = cases.get(0);
        assertEquals("HIGH", c.get("ruleBasedDropOffRisk"));
        assertFalse((Boolean) c.get("appointmentExists"));
        List<?> reasons = (List<?>) c.get("reasons");
        assertTrue(reasons.contains("no appointment booked after 7 days"));
    }

    @Test
    @DisplayName("Care loop deterministic rules: Rescheduled appointment triggers Rule 2")
    void careLoopLogic_rescheduledAppointment_triggersRule2() {
        AshaHead head = new AshaHead();
        head.setId(supervisorHeadId);

        Asha asha = new Asha();
        asha.setId(ashaId);
        asha.setName("Sunita Tai");
        asha.setHead(head);

        Referral referral = new Referral();
        referral.setId(UUID.randomUUID());
        referral.setAsha(asha);
        referral.setStatus("pending");
        referral.setReferredDate(OffsetDateTime.now().minusDays(3)); // 3 days ago

        NgoAppointment appt = new NgoAppointment();
        appt.setId(UUID.randomUUID());
        appt.setReferral(referral);
        appt.setStatus("rescheduled");
        appt.setScheduledAt(OffsetDateTime.now().plusDays(2));

        when(referralRepository.findByAsha_Head_Id(supervisorHeadId)).thenReturn(List.of(referral));
        when(ngoAppointmentRepository.findByReferral_IdOrderByScheduledAtDesc(referral.getId())).thenReturn(List.of(appt));
        when(editHistoryRepository.findByRecordIdOrderByEditedAtDesc(referral.getId())).thenReturn(Collections.emptyList());

        AdminService realAdminService = new AdminService(
                null, null, null, null, null,
                referralRepository, null, null, null, null, null, null,
                visitRepository, null, null, null, null,
                ngoAppointmentRepository, editHistoryRepository
        );

        List<Map<String, Object>> cases = realAdminService.getCareLoopCases(supervisorHeadId, false);

        assertEquals(1, cases.size());
        Map<String, Object> c = cases.get(0);
        assertEquals("MEDIUM", c.get("ruleBasedDropOffRisk"));
        assertTrue((Boolean) c.get("appointmentExists"));
        List<?> reasons = (List<?>) c.get("reasons");
        assertTrue(reasons.contains("appointment rescheduled or missed"));
    }

    @Test
    @DisplayName("Care loop deterministic rules: Rejected referral triggers Rule 3 and HIGH risk")
    void careLoopLogic_rejectedReferral_highRisk() {
        AshaHead head = new AshaHead();
        head.setId(supervisorHeadId);

        Asha asha = new Asha();
        asha.setId(ashaId);
        asha.setName("Sunita Tai");
        asha.setHead(head);

        Referral referral = new Referral();
        referral.setId(UUID.randomUUID());
        referral.setAsha(asha);
        referral.setStatus("rejected");
        referral.setReferredDate(OffsetDateTime.now().minusDays(2));

        when(referralRepository.findByAsha_Head_Id(supervisorHeadId)).thenReturn(List.of(referral));
        when(ngoAppointmentRepository.findByReferral_IdOrderByScheduledAtDesc(referral.getId())).thenReturn(Collections.emptyList());
        when(editHistoryRepository.findByRecordIdOrderByEditedAtDesc(referral.getId())).thenReturn(Collections.emptyList());

        AdminService realAdminService = new AdminService(
                null, null, null, null, null,
                referralRepository, null, null, null, null, null, null,
                visitRepository, null, null, null, null,
                ngoAppointmentRepository, editHistoryRepository
        );

        List<Map<String, Object>> cases = realAdminService.getCareLoopCases(supervisorHeadId, false);

        assertEquals(1, cases.size());
        Map<String, Object> c = cases.get(0);
        assertEquals("HIGH", c.get("ruleBasedDropOffRisk"));
        List<?> reasons = (List<?>) c.get("reasons");
        assertTrue(reasons.contains("referral rejected"));
    }
}
