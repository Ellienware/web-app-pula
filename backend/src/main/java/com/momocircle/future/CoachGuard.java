package com.momocircle.future;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic parts of the PULA Future coach. The coach EXPLAINS figures that the backend already calculated; it never
 * calculates, decides balances or payment status, or triggers anything. This class (a) redacts sensitive text before any
 * external AI call, (b) checks AI output against a deny-list, and (c) provides a complete rule-based fallback so the
 * coach is useful with no AI configured.
 */
public final class CoachGuard {

    public static final String DISCLAIMER =
            "PULA Future is not a licensed financial adviser. This is general education based on your own planning figures; "
          + "nothing here is a recommendation, not a guarantee of any result, and not an instruction to move money.";

    /** Server-calculated figures, in cents. Null context = no plan figures were supplied. */
    public record Context(Boolean valid, Long incomeBasisMinor, Long allocatableMinor, Long totalAllocatedMinor,
                          Long remainingMinor, List<String> warnings) {}

    public record Answer(String text, String source) {}

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{4,}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern NEGATED_GUARANTEE = Pattern.compile(
            "(?i)(not|never|no|n't|cannot|can't)\\s+(be\\s+|a\\s+|any\\s+|ever\\s+)?guarantee[sd]?|no guarantees?|without guarantee");
    private static final Pattern GUARANTEE = Pattern.compile("(?i)\\bguarantee[sd]?\\b|risk[- ]free|can't lose|cannot lose");
    private static final Pattern ADVICE = Pattern.compile(
            "(?i)\\b(you should (buy|sell|invest in|put your money)|i (recommend|advise) (that you )?(buy|invest|sell)|"
          + "i am (a|your) (licensed |registered )?(financial )?advis[eo]r|will (definitely|certainly|surely) (earn|make|grow|double))\\b");
    private static final Pattern ACTION_CLAIM = Pattern.compile(
            "(?i)\\bi (have |'ve )?(transferred|paid|sent|debited|contributed|invested|bought|purchased|moved)\\b");

    private CoachGuard() {}

    /** Removes digit runs (PINs, OTPs, account/card numbers), emails and caps length. Applied before ANY external AI call. */
    public static String redact(String question) {
        if (question == null) return "";
        String q = EMAIL.matcher(question).replaceAll("[email removed]");
        q = DIGIT_RUN.matcher(q).replaceAll("[number removed]");
        q = q.trim();
        return q.length() > 500 ? q.substring(0, 500) : q;
    }

    /** True when AI output is safe to show. Anything doubtful is rejected and the deterministic fallback is used instead. */
    public static boolean isSafe(String text) {
        if (text == null || text.isBlank() || text.length() > 2000) return false;
        String withoutNegations = NEGATED_GUARANTEE.matcher(text).replaceAll(" ");
        if (GUARANTEE.matcher(withoutNegations).find()) return false;
        if (ADVICE.matcher(text).find()) return false;
        return !ACTION_CLAIM.matcher(text).find();
    }

    public static Answer fallback(String question, Context ctx) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String text;
        if (has(q, "scam", "guaranteed", "double my money", "get rich", "pyramid", "ponzi", "whatsapp group", "too good")) {
            text = "Warning signs of an investment scam include promises of high returns that sound too good to be true, pressure to act quickly, secrecy, "
                 + "asking you to recruit others, and requests for PINs or one-time codes. Real investments can lose value. "
                 + "Verify the provider through an official channel you find yourself, and never share your PIN or codes.";
        } else if (has(q, "why", "exceed", "too much", "warning", "invalid", "over", "not enough", "buffer") && ctx != null && ctx.warnings() != null && !ctx.warnings().isEmpty()) {
            text = "Your scenario shows these warnings: " + String.join(" ", ctx.warnings()) + " "
                 + "In plain terms, the amounts you proposed need more than what is left after your declared reserves and commitments. "
                 + "You could lower a percentage or fixed amount, or revisit the reserves you declared. This is a planning view only; no money has moved.";
        } else if (has(q, "fee", "charges", "cost")) {
            text = "A fee is taken from the whole balance every year, whether the investment did well or not, so small percentages add up over decades. "
                 + "For example, a 10% return with a 2% fee behaves like 8% before inflation. Use the simulator with 0% and then your fee to compare.";
        } else if (has(q, "inflation", "purchasing", "buying power", "worth in today")) {
            text = "Inflation means prices rise over time, so the same rand buys less. The simulator shows a future value and a value in today's buying power. "
                 + "Savings that earn less than inflation lose purchasing power even though the balance does not fall.";
        } else if (has(q, "compound", "interest on interest")) {
            text = "Compound growth means you earn growth on your earlier growth as well as on your starting amount. The longer the time, the bigger the effect. "
                 + "Real returns vary and can be negative in some years.";
        } else if (has(q, "emergency", "rainy day")) {
            text = "An emergency fund is cash set aside for surprises such as a medical bill or a gap in income. Many people aim for a few months of essential expenses, "
                 + "but the right amount depends on your situation. Keep it somewhere you can reach quickly.";
        } else if (has(q, "etf", "unit trust", "money market", "money-market", "share", "diversif", "liquid", "retirement annuity", "retirement")) {
            text = "These products differ in risk, fees and how quickly you can get your money. Shares and ETFs can fall as well as rise; unit trusts pool money in a managed fund; "
                 + "money-market funds are generally lower risk but are not the same as a bank deposit; retirement products often limit early access. "
                 + "Open the Learn tab for plain-language lessons, and check any provider is authorised before you commit.";
        } else if (has(q, "split", "break", "monthly", "how much", "per month", "schedule", "afford") && ctx != null && ctx.remainingMinor() != null) {
            text = "Based on your last calculation, " + rand(ctx.totalAllocatedMinor()) + " is proposed out of " + rand(ctx.allocatableMinor())
                 + " available, leaving " + rand(ctx.remainingMinor()) + " unallocated. For a single goal, open the Goals tab and use Estimate to see a suggested weekly or monthly amount.";
        } else if (has(q, "what is", "mean", "explain", "define", "difference")) {
            text = "Happy to explain. The Learn tab has short lessons and a glossary covering saving versus investing, inflation, fees, risk, diversification and more. "
                 + "Ask me about one term at a time and I will keep it plain.";
        } else {
            text = "I can explain your allocation warnings, how fees and inflation affect a projection, common product types, and how to spot scams. "
                 + "I do not move money, recommend products, or predict returns. Try asking, for example, 'why does my plan show a warning?' or 'how do fees affect my savings?'";
        }
        return new Answer(text + " " + DISCLAIMER, "fallback");
    }

    private static boolean has(String q, String... needles) {
        for (String n : needles) if (q.contains(n)) return true;
        return false;
    }

    private static String rand(Long minor) {
        if (minor == null) return "R0.00";
        return String.format(Locale.ROOT, "R%d.%02d", minor / 100, Math.abs(minor % 100));
    }
}
