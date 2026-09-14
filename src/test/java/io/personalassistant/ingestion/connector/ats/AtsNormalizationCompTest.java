package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.ingestion.connector.ats.AtsNormalization.CompRange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Compensation parsing, including the Indian notation the original US/EU-only pattern could not read
 * at all.
 *
 * <p>The currency assertions matter as much as the numbers: {@code compMin}/{@code compMax} are plain
 * numbers in the index, so a corpus mixing INR and USD would make one numeric filter mean two things.
 */
class AtsNormalizationCompTest {

    private static CompRange parse(String text) {
        return AtsNormalization.compRange(text);
    }

    // ---- western forms (unchanged behaviour) --------------------------------------------------

    @Test
    void readsADollarRange() {
        CompRange comp = parse("Base salary $150,000 - $190,000 plus equity.");

        Assertions.assertEquals(150_000, comp.min());
        Assertions.assertEquals(190_000, comp.max());
        Assertions.assertEquals("USD", comp.currency());
    }

    @Test
    void readsAThousandsSuffix() {
        CompRange comp = parse("We pay USD 150k–190k for this level.");

        Assertions.assertEquals(150_000, comp.min());
        Assertions.assertEquals(190_000, comp.max());
    }

    // ---- indian forms -------------------------------------------------------------------------

    @Test
    void readsIndianDigitGrouping() {
        // 15,00,000 is fifteen lakh. A western-only (?:,\d{3})* matches just the leading "15" and
        // silently yields fifteen — the failure that motivated this.
        CompRange comp = parse("Compensation: ₹15,00,000 - ₹25,00,000 per annum");

        Assertions.assertEquals(1_500_000, comp.min());
        Assertions.assertEquals(2_500_000, comp.max());
        Assertions.assertEquals("INR", comp.currency());
    }

    @Test
    void readsLpaWithTheUnitStatedOnlyOnce() {
        // "18-30 LPA" is how Indian postings actually write it: amount first, unit once, at the end,
        // and no currency symbol anywhere.
        CompRange comp = parse("CTC: 18-30 LPA depending on experience.");

        Assertions.assertEquals(1_800_000, comp.min());
        Assertions.assertEquals(3_000_000, comp.max());
        Assertions.assertEquals("INR", comp.currency());
    }

    @Test
    void readsLakhAndRupeeWordForms() {
        CompRange lakhs = parse("Offering Rs. 12 lakh to 18 lakh annually.");
        Assertions.assertEquals(1_200_000, lakhs.min());
        Assertions.assertEquals(1_800_000, lakhs.max());
        Assertions.assertEquals("INR", lakhs.currency());
    }

    @Test
    void readsCroreForSeniorBands() {
        CompRange comp = parse("Total compensation ₹1 crore - ₹2 crore.");

        Assertions.assertEquals(10_000_000, comp.min());
        Assertions.assertEquals(20_000_000, comp.max());
    }

    // ---- what must NOT parse -------------------------------------------------------------------

    @Test
    void ignoresYearsOfExperience() {
        Assertions.assertNull(parse("Looking for 2 - 5 years of experience in backend systems."));
    }

    @Test
    void ignoresAMonthlyFigureRatherThanReadingItAsAnnual() {
        // Indian postings quote monthly for junior roles often enough that reading one as annual
        // overstates pay 12x. There is no safe conversion, so it is skipped.
        Assertions.assertNull(parse("Stipend of ₹50,000 - ₹80,000 per month for the internship."));
    }

    @Test
    void ignoresAnImplausiblyLowInrFigure() {
        // 5,000-8,000 is a monthly stipend or a per-day rate, never an annual salary. The floor is
        // per-currency: 50,000 is implausible as USD annual but ordinary as INR monthly.
        Assertions.assertNull(parse("Allowance of ₹5,000 - ₹8,000."));
    }

    @Test
    void ignoresAValuationRatherThanReadingItAsPay() {
        // The exact false positive found in real postings: "we reached a valuation of $2 billion".
        Assertions.assertNull(parse("Backed by SoftBank, we reached a valuation of $2 billion in 2025."));
    }

    @Test
    void ignoresADurationInMonths() {
        // Found on live boards: an "m" alternative for "million" read "6-12 months" as six to twelve
        // MILLION, fabricating a salary band on a posting that never mentioned pay. Every false
        // positive the parser had came from that one alternative.
        Assertions.assertNull(parse("This is a 6-12 months contract role."));
        Assertions.assertNull(parse("Expect the interview to take 20-30 minutes."));
    }

    @Test
    void ignoresAnUnmarkedNumberRange() {
        // Also found live: with no currency and no unit, "200,000 - 300,000" is just two numbers.
        // Every match has to be anchored by one or the other.
        Assertions.assertNull(parse("Our platform serves 200,000 - 300,000 requests per second."));
    }

    @Test
    void ignoresAnInvertedRange() {
        Assertions.assertNull(parse("Salary $190,000 - $150,000"));
    }

    @Test
    void returnsNullWhenNoPayIsStatedAtAll() {
        // Overwhelmingly the common case on these boards for Indian roles.
        Assertions.assertNull(parse("Join our team building distributed systems in Bengaluru."));
        Assertions.assertNull(parse(""));
        Assertions.assertNull(parse(null));
    }
}
