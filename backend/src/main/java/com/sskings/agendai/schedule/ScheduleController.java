package com.sskings.agendai.schedule;

import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import com.sskings.agendai.schedule.ScheduleDtos.TimeOffRequest;
import com.sskings.agendai.schedule.ScheduleDtos.TimeOffView;
import com.sskings.agendai.schedule.ScheduleDtos.WorkingHoursRequest;
import com.sskings.agendai.schedule.ScheduleDtos.WorkingHoursView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService schedule;

    @GetMapping("/professionals/{professionalId}/working-hours")
    public WorkingHoursView workingHours(@PathVariable UUID tenantId, @PathVariable UUID professionalId) {
        return schedule.workingHours(tenantId, professionalId);
    }

    @PutMapping("/professionals/{professionalId}/working-hours")
    public WorkingHoursView replaceWorkingHours(@PathVariable UUID tenantId, @PathVariable UUID professionalId,
                                                @Valid @RequestBody WorkingHoursRequest body) {
        return schedule.replaceWorkingHours(tenantId, professionalId, body);
    }

    @GetMapping("/time-off")
    public List<TimeOffView> timeOff(@PathVariable UUID tenantId,
                                     @RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DATE) LocalDate to) {
        return schedule.timeOff(tenantId, from, to);
    }

    @PostMapping("/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeOffView createTimeOff(@PathVariable UUID tenantId, @Valid @RequestBody TimeOffRequest body) {
        return schedule.createTimeOff(tenantId, body);
    }

    @DeleteMapping("/time-off/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeOff(@PathVariable UUID tenantId, @PathVariable UUID id) {
        schedule.deleteTimeOff(tenantId, id);
    }

    @GetMapping("/availability")
    public AvailabilityView availability(@PathVariable UUID tenantId, @RequestParam UUID professionalId, @RequestParam UUID serviceId,
                                         @RequestParam @DateTimeFormat(iso = DATE) LocalDate date) {
        return schedule.availability(tenantId, professionalId, serviceId, date);
    }
}
