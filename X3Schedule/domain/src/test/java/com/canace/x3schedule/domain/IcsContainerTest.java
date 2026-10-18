package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import java.time.LocalDate;
import org.junit.Test;

public class IcsContainerTest {
    private String event() {
        return "BEGIN:VEVENT\r\nUID:synthetic\r\nDTSTART:20260927T030000Z\r\nDTEND:20260927T040000Z\r\n"
                + "SUMMARY:合成提醒\r\nEND:VEVENT\r\n";
    }

    private String calendar() {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + event() + "END:VCALENDAR\r\n";
    }

    private void reject(String text) {
        assertThrows(IllegalArgumentException.class, () -> Ics.preview(text));
    }

    @Test
    public void thirdPartyEventsDefaultToReminder() {
        assertEquals(Kind.REMINDER, Ics.preview(calendar()).get(0).kind);
    }

    @Test
    public void bothTimedKindsRoundTripWithoutLosingPurpose() {
        for (Kind kind : new Kind[] { Kind.EXECUTION, Kind.REMINDER }) {
            Plan plan = new Plan();
            plan.kind = kind;
            plan.title = "合成类型";
            plan.rule = Rule.DAYS;
            plan.anchor = "2026-09-27";
            LocalDate date = LocalDate.parse(plan.anchor);
            String text = Ics.export(Recurrence.generate(plan, date, date));
            assertEquals(kind, Ics.preview(text).get(0).kind);
        }
    }

    @Test
    public void truncatedAndConcatenatedCalendarsAreRejected() {
        reject(calendar().replace("END:VCALENDAR\r\n", ""));
        reject(calendar() + calendar());
        reject(event() + calendar());
        reject(calendar() + event());
    }

    @Test
    public void calendarMarkersInPropertyTextDoNotCountAsAContainer() {
        reject("COMMENT:BEGIN:VCALENDAR VERSION:2.0 END:VCALENDAR\r\n" + event());
    }

    @Test
    public void nestedOrUnsupportedComponentsAreRejected() {
        reject(calendar().replace("END:VEVENT", "BEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VEVENT"));
        reject(calendar().replace("END:VEVENT", "BEGIN:VALARM\r\nEND:VALARM\r\nEND:VEVENT"));
        reject(calendar().replace("BEGIN:VEVENT", "BEGIN:VTODO").replace("END:VEVENT", "END:VTODO"));
    }

    @Test
    public void wrongVersionAndCancellationMessagesAreRejected() {
        reject(calendar().replace("VERSION:2.0", "VERSION:1.0"));
        reject(calendar().replace("VERSION:2.0", "VERSION:2.0\r\nVERSION:2.0"));
        reject(calendar().replace("VERSION:2.0", "VERSION:2.0\r\nMETHOD:CANCEL"));
        reject(calendar().replace("VERSION:2.0", "VERSION:2.0\r\nMETHOD:REQUEST"));
    }

    @Test
    public void unknownPurposeOrTodoCannotBeMisreadAsExecution() {
        reject(calendar().replace("SUMMARY:", "X-X3SCHEDULE-KIND:TODO\r\nSUMMARY:"));
        reject(calendar().replace("SUMMARY:", "X-X3SCHEDULE-KIND:UNKNOWN\r\nSUMMARY:"));
    }

    @Test
    public void exportingTodoFailsWithExplicitUnsupportedScope() {
        Plan todo = new Plan();
        todo.title = "合成待办";
        todo.kind = Kind.TODO;
        todo.rule = Rule.DAYS;
        todo.count = 1;
        LocalDate date = LocalDate.parse(todo.anchor);
        assertThrows(IllegalArgumentException.class, () -> Ics.export(Recurrence.generate(todo, date, date)));
    }
}
