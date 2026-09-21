package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.EffectiveRole.Candidate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The role rule of 2026-09-21 (second revision): the job title says who leads, and a leader nobody reports to
 * is a member. WorkloadHub's own `users.role` is MEMBER for all but a handful of accounts, so it is trusted
 * only for the three roles no job title implies.
 */
class EffectiveRoleTest {

    // Every leader-ish job title of the owner's real directory, pinned so a change to the patterns cannot
    // silently reclassify anyone. The four "manager"/"lead" titles at the end are members on purpose.
    @ParameterizedTest
    @CsvSource({
        "Team Leader Design,TEAM_LEADER",
        "Team Leader HR,TEAM_LEADER",
        "Team Leader Calibration,TEAM_LEADER",
        "Team Leader Customer Site Coordination,TEAM_LEADER",
        "Team Leader Electric/ Electronics,TEAM_LEADER",
        "Team Leader System Development,TEAM_LEADER",
        "Team Leader Functional Development,TEAM_LEADER",
        "Team Leader Data Intelligence & AI,TEAM_LEADER",
        "Team leader PWT Calibration,TEAM_LEADER",
        "Team Leader Software MBD Engineering,TEAM_LEADER",
        "Lead Engineer DAI & AI,TEAM_LEADER",
        "Lead Engineer Battery,TEAM_LEADER",
        "Lead Engineer Calibration and Testing,TEAM_LEADER",
        "Lead Engineer System engineering,TEAM_LEADER",
        "Calibration Lead Engineer,TEAM_LEADER",
        "SW Lead Engineer,TEAM_LEADER",
        "Skill Team Leader,SKILL_TEAM_LEADER",
        "Engineering Center Manager,CENTER_MANAGER",
        "Project Manager PTE,MEMBER",
        "Workshop Manager,MEMBER",
        "Calibration Quality & Dataset Manager,MEMBER",
        "Performance & Efficiency Lead,MEMBER",
        "Verification & Validation Engineer,MEMBER",
        "HR Specialist,MEMBER"
    })
    void theTitleSaysWhoLeads(String jobTitle, String expected) {
        assertEquals(expected, EffectiveRole.fromTitle("MEMBER", jobTitle));
    }

    @Test
    void skillTeamLeaderIsTestedBeforeTeamLeader() {
        // "Skill Team Leader" contains "team lead": the wrong order would make every skill team leader a
        // forecast subject, which the standing rule forbids.
        assertEquals("SKILL_TEAM_LEADER", EffectiveRole.fromTitle("MEMBER", "Skill Team Leader"));
    }

    @Test
    void caseAndSpacingDoNotMatter() {
        assertEquals("TEAM_LEADER", EffectiveRole.fromTitle("MEMBER", "TEAM LEADER DESIGN"));
        assertEquals("TEAM_LEADER", EffectiveRole.fromTitle("MEMBER", "  Team\tLeader   Design  "));
        assertEquals("SKILL_TEAM_LEADER", EffectiveRole.fromTitle("MEMBER", "skill  team  leader"));
    }

    @Test
    void anAbsentTitleIsAMember() {
        assertEquals("MEMBER", EffectiveRole.fromTitle("MEMBER", null));
        assertEquals("MEMBER", EffectiveRole.fromTitle("MEMBER", "   "));
    }

    @Test
    void theThreeDeclaredRolesWinOverTheTitle() {
        // No job title implies them, and the application assigns them deliberately.
        assertEquals("ADMIN", EffectiveRole.fromTitle("ADMIN", "Team Leader Design"));
        assertEquals("CENTER_MANAGER", EffectiveRole.fromTitle("CENTER_MANAGER", null));
        assertEquals("VIEWER", EffectiveRole.fromTitle("VIEWER", "Lead Engineer Battery"));
    }

    @Test
    void aDeclaredLeaderRoleDoesNotWinOverTheTitle() {
        // The owner's database says MEMBER for 260 of 264 people and TEAM_LEADER for two accounts that lead
        // nobody, so the column is not evidence of leadership.
        assertEquals("MEMBER", EffectiveRole.fromTitle("TEAM_LEADER", "Verification & Validation Engineer"));
        assertEquals("MEMBER", EffectiveRole.fromTitle("SKILL_TEAM_LEADER", null));
    }

    @Test
    void aLeaderNobodyReportsToIsAMember() {
        UUID lead = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(lead, null, "MEMBER", "SW Lead Engineer", true)));
        assertEquals("MEMBER", roles.get(lead));
    }

    @Test
    void aLeaderWithOneCountedReportStaysALeader() {
        UUID lead = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(lead, null, "MEMBER", "Team Leader Design", true),
                new Candidate(member, lead, "MEMBER", "Design Engineer", true)));
        assertEquals("TEAM_LEADER", roles.get(lead));
        assertEquals("MEMBER", roles.get(member));
    }

    @Test
    void anInactiveReportDoesNotMakeALeader() {
        UUID lead = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(lead, null, "MEMBER", "Team Leader Design", true),
                new Candidate(gone, lead, "MEMBER", "Design Engineer", false)));
        assertEquals("MEMBER", roles.get(lead));
    }

    @Test
    void anUncountedReportDoesNotMakeALeader() {
        // A team leader whose only report is a skill team leader leads nobody the forecast can see.
        UUID lead = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(lead, null, "MEMBER", "Team Leader Design", true),
                new Candidate(skill, lead, "MEMBER", "Skill Team Leader", true)));
        assertEquals("MEMBER", roles.get(lead));
    }

    @Test
    void aSelfManagingUserIsNotTheirOwnReport() {
        UUID lead = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(lead, lead, "MEMBER", "Team Leader Design", true)));
        assertEquals("MEMBER", roles.get(lead));
    }

    @Test
    void anActorWithNoReportsIsNotDemoted() {
        // Demoting them would make them a forecast subject, and neither does technical work.
        UUID skill = UUID.randomUUID();
        UUID centre = UUID.randomUUID();
        Map<UUID, String> roles = EffectiveRole.resolve(List.of(
                new Candidate(skill, null, "MEMBER", "Skill Team Leader", true),
                new Candidate(centre, null, "MEMBER", "Engineering Center Manager", true)));
        assertEquals("SKILL_TEAM_LEADER", roles.get(skill));
        assertEquals("CENTER_MANAGER", roles.get(centre));
    }

    @Test
    void demotionNeverChangesWhoIsCounted() {
        // The rule is well defined only because both sides of the demotion are counted roles: whether a user
        // is counted is fixed by their title alone and never depends on the demotion of anyone else.
        UUID lead = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        List<Candidate> all = List.of(
                new Candidate(lead, null, "MEMBER", "SW Lead Engineer", true),
                new Candidate(member, lead, "MEMBER", "Design Engineer", true));
        Map<UUID, String> roles = EffectiveRole.resolve(all);
        assertTrue(EffectiveRole.counted(roles.get(lead), true));
        assertTrue(EffectiveRole.counted(roles.get(member), true));
        assertFalse(EffectiveRole.counted("SKILL_TEAM_LEADER", true));
        assertFalse(EffectiveRole.counted("MEMBER", false));
    }

    @Test
    void theOrderOfTheInputDoesNotMatter() {
        UUID lead = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Candidate l = new Candidate(lead, null, "MEMBER", "Team Leader Design", true);
        Candidate m = new Candidate(member, lead, "MEMBER", "Design Engineer", true);
        assertEquals(EffectiveRole.resolve(List.of(l, m)), EffectiveRole.resolve(List.of(m, l)));
    }
}
