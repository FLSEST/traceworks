package traceworks.core.record;

import traceworks.core.port.BlobRef;

/**
 * A gate verdict as the record shows it.
 *
 * @param status      {@code GREEN}, {@code YELLOW} or {@code RED}
 * @param category    the category the step reported; {@code null} when none
 * @param description the description blob; {@code null} when none
 * @param summary     the summary blob of a run's verdict; {@code null} for a step's verdict and when none
 */
public record GateView(String status, String category, BlobRef description, BlobRef summary) {}
