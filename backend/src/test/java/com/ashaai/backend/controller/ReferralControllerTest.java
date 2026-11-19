package com.ashaai.backend.controller;

import com.ashaai.backend.entity.Child;
import com.ashaai.backend.entity.EditHistory;
import com.ashaai.backend.entity.PendingReview;
import com.ashaai.backend.entity.Referral;
import com.ashaai.backend.repository.*;
import com.ashaai.backend.security.AshaAuthenticationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReferralControllerTest {

    private MockMvc mockMvc;
    private ReferralRepository referralRepository;
    private ChildRepository childRepository;
    private AshaRepository ashaRepository;
    private HouseholdMemberRepository householdMemberRepository;
    private EditHistoryRepository editHistoryRepository;
    private PendingReviewRepository pendingReviewRepository;
    private ObjectMapper objectMapper;

    private final UUID ashaId = UUID.randomUUID();
    private final UUID supervisorId = UUID.randomUUID();
    private final UUID referralId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralRepository = mock(ReferralRepository.class);
        childRepository = mock(ChildRepository.class);
        ashaRepository = mock(AshaRepository.class);
        householdMemberRepository = mock(HouseholdMemberRepository.class);
        editHistoryRepository = mock(EditHistoryRepository.class);
        pendingReviewRepository = mock(PendingReviewRepository.class);
        objectMapper = new ObjectMapper();

        ReferralController controller = new ReferralController(
                referralRepository,
                childRepository,
                ashaRepository,
                householdMemberRepository,
                editHistoryRepository,
                pendingReviewRepository
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AshaAuthenticationToken createAshaAuth() {
        return new AshaAuthenticationToken(
                "asha_user",
                ashaId,
                null, // No supervisor ID
                UUID.randomUUID(),
                List.of(new SimpleGrantedAuthority("ROLE_ASHA"))
        );
    }

    private AshaAuthenticationToken createSupervisorAuth() {
        return new AshaAuthenticationToken(
                "supervisor_user",
                null,
                supervisorId, // Supervisor ID present
                UUID.randomUUID(),
                List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))
        );
    }

    @Test
    @DisplayName("Outcome 1: ASHA calling close endpoint returns 403 Forbidden")
    void ashaCallingCloseEndpoint_returns403() throws Exception {
        Referral referral = new Referral();
        referral.setStatus("admitted");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        Map<String, String> body = Map.of("evidence", "Discharge summary verified and signed by Dr. Sharma");

        mockMvc.perform(post("/api/referrals/" + referralId + "/close")
                        .principal(createAshaAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Only supervisors (asha_heads) can close referrals"));

        verify(referralRepository, never()).save(any());
        verify(editHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Outcome 2: Supervisor calling close without evidence (under 10 chars) returns 422")
    void supervisorWithoutEvidence_returns422() throws Exception {
        Referral referral = new Referral();
        referral.setStatus("admitted");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        // Short evidence (< 10 characters)
        Map<String, String> body = Map.of("evidence", "ok closed");

        mockMvc.perform(post("/api/referrals/" + referralId + "/close")
                        .principal(createSupervisorAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value("Evidence note is required and must be at least 10 characters long"));

        // Null evidence test
        mockMvc.perform(post("/api/referrals/" + referralId + "/close")
                        .principal(createSupervisorAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        verify(referralRepository, never()).save(any());
        verify(editHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Outcome 3: Calling close on an already closed referral returns 409 Conflict")
    void alreadyClosedReferral_returns409() throws Exception {
        Referral referral = new Referral();
        referral.setStatus("closed");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        Map<String, String> body = Map.of("evidence", "Discharge summary verified and signed by Dr. Sharma");

        mockMvc.perform(post("/api/referrals/" + referralId + "/close")
                        .principal(createSupervisorAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Referral is already closed"));

        verify(referralRepository, never()).save(any());
        verify(editHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Outcome 4: Supervisor with valid evidence closes referral and records EditHistory in same transaction")
    void supervisorWithValidEvidence_closesReferralAndRecordsEditHistory() throws Exception {
        Referral referral = new Referral();
        referral.setStatus("awaiting_verification");

        Child child = new Child();
        child.setNrcReferralStatus("awaiting_verification");
        referral.setChild(child);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(any(Referral.class))).thenAnswer(i -> i.getArgument(0));

        PendingReview pendingReview = new PendingReview();
        pendingReview.setTableName("referrals");
        pendingReview.setRecordId(referralId);
        pendingReview.setStatus("PENDING");
        when(pendingReviewRepository.findByRecordId(referralId)).thenReturn(List.of(pendingReview));

        String evidenceText = "Discharge summary #NRC-9812 verified. Child weight stabilized at 9.8kg.";
        Map<String, String> body = Map.of("evidence", evidenceText);

        mockMvc.perform(post("/api/referrals/" + referralId + "/close")
                        .principal(createSupervisorAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Referral successfully verified and closed"))
                .andExpect(jsonPath("$.referral.status").value("closed"));

        assertThat(referral.getStatus()).isEqualTo("closed");
        assertThat(child.getNrcReferralStatus()).isEqualTo("closed");
        assertThat(pendingReview.getStatus()).isEqualTo("CONFIRMED");

        // Verify EditHistory row
        ArgumentCaptor<EditHistory> historyCaptor = ArgumentCaptor.forClass(EditHistory.class);
        verify(editHistoryRepository).save(historyCaptor.capture());
        EditHistory history = historyCaptor.getValue();

        assertThat(history.getTableName()).isEqualTo("referrals");
        assertThat(history.getFieldName()).isEqualTo("status");
        assertThat(history.getOldValue()).isEqualTo("awaiting_verification");
        assertThat(history.getNewValue()).isEqualTo("closed");
        assertThat(history.getEditedBy()).isEqualTo(supervisorId);
        assertThat(history.getReason()).contains("[supervisor_verified]");
        assertThat(history.getReason()).contains(evidenceText);
    }

    @Test
    @DisplayName("ASHA can request closure transitioning referral to awaiting_verification and creating PendingReview")
    void ashaCanRequestClosure() throws Exception {
        Referral referral = new Referral();
        referral.setStatus("admitted");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(any(Referral.class))).thenAnswer(i -> i.getArgument(0));

        mockMvc.perform(post("/api/referrals/" + referralId + "/request-closure")
                        .principal(createAshaAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("notes", "Child completed 14-day protocol"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referral.status").value("awaiting_verification"));

        assertThat(referral.getStatus()).isEqualTo("awaiting_verification");

        ArgumentCaptor<PendingReview> prCaptor = ArgumentCaptor.forClass(PendingReview.class);
        verify(pendingReviewRepository).save(prCaptor.capture());
        PendingReview pr = prCaptor.getValue();
        assertThat(pr.getTableName()).isEqualTo("referrals");
        assertThat(pr.getStatus()).isEqualTo("PENDING");
        assertThat(pr.getReason()).isEqualTo("Child completed 14-day protocol");
    }
}
