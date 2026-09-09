package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkFamilyTest {

    @ParameterizedTest
    @CsvSource({
        "Calibration Engineer, CALIBRATION",
        "Team Leader Calibration, CALIBRATION",
        "Calibration Quality & Dataset Manager, CALIBRATION",
        "Data Analyst & SW Developer, DATA",
        "AI Engineer, DATA",
        "Lead Engineer DAI & AI, DATA",
        "IT System Administrator, SUPPORT",
        "HR Business Partner, SUPPORT",
        "Purchasing & Admin Officer, SUPPORT",
        "Facility Worker, SUPPORT",
        "System Development Engineer, SYSTEMS",
        "System Devlopment Engineer, SYSTEMS",
        "Lead Engineer System engineering, SYSTEMS",
        "Development Eng. Electric/ Electronics, ELECTRONICS",
        "Software & Functions Engineer, ELECTRONICS",
        "Development Engineer SW, ELECTRONICS",
        "Verification & Validation Engineer, VALIDATION",
        "Vehicule Fleet Validation Engineer, VALIDATION",
        "Attributes & Homologation Engineer, VALIDATION",
        "Design Engineer DMU, DESIGN",
        "Simulation Engineer CFD, DESIGN",
        "Project Manager PTE, COORDINATION",
        "Team Leader Customer Site Coordination, COORDINATION",
        "Skill Team Leader, COORDINATION",
        "Engineering Center Manager, COORDINATION",
        "Workshop Manager, COORDINATION",
        "Astronaut, UNKNOWN",
        "'', UNKNOWN"
    })
    void classifiesTitles(String title, WorkFamily expected) {
        assertEquals(expected, WorkFamily.classify(title));
    }

    @ParameterizedTest
    @CsvSource({"CALIBRATION, 12, 32", "COORDINATION, 6, 16", "SUPPORT, 4, 20"})
    void carriesTheDesignParameters(WorkFamily f, double median, double weekly) {
        assertEquals(median, f.medianEstimate);
        assertEquals(weekly, f.weeklyHours);
        assertEquals(1.0, f.delivery + f.defect + f.support + f.container, 1e-9);
    }
}
