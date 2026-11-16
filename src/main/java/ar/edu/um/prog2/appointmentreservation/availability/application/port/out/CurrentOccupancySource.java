package ar.edu.um.prog2.appointmentreservation.availability.application.port.out;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Current blocked positions for one professional and date, independently of their transport. */
@FunctionalInterface
public interface CurrentOccupancySource {

	Set<LocalTime> loadBlockedStartTimes(long professionalId, LocalDate date);
}
