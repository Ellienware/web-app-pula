package com.momocircle.future;

import java.util.List;
import java.util.Optional;

/**
 * Static, general-purpose financial education. Deliberately contains no tax rates, regulatory limits, provider names,
 * product recommendations or return promises: those change and must be verified with a current official source.
 * Rand examples are arithmetic illustrations only. Not financial advice.
 */
public final class EducationContent {
    public static final String DISCLAIMER =
            "General education only. PULA is not a financial adviser and this is not advice for your situation. "
          + "Rand figures are illustrations, not predictions or promises.";

    public record Lesson(String id, String title, String level, String summary, List<String> body, String tryIt) {}
    public record Term(String term, String meaning) {}

    private static final List<Lesson> LESSONS = List.of(
        new Lesson("saving-vs-investing", "Saving vs investing", "BEGINNER",
            "Saving keeps money safe and easy to reach. Investing accepts risk for the chance of growth.",
            List.of("Saving is for money you may need soon, such as an emergency or next month's rent. The value usually stays steady and you can reach it quickly.",
                    "Investing means putting money into things like shares or funds whose value moves up and down. Over long periods they may grow more than savings, but they can also lose value, especially over short periods.",
                    "A common rule of thumb: money needed within a few years belongs in savings; money you will not need for many years can be considered for investing, once an emergency fund is in place."),
            "Use the simulator with a 0% return and then 8%. Notice that the 8% case is an assumption, not a promise."),
        new Lesson("emergency-fund", "Why an emergency fund comes first", "BEGINNER",
            "A cash cushion stops a surprise bill from forcing you to sell investments or borrow.",
            List.of("An emergency fund covers unexpected costs such as medical bills, a repair or a gap in income.",
                    "Many people aim for a few months of essential expenses. The right amount depends on your income stability and responsibilities.",
                    "Keep it somewhere you can reach quickly and that does not fluctuate in value."),
            "Create a goal with the Emergency fund category and use the schedule estimate to see a monthly amount."),
        new Lesson("compound-growth", "Compound growth", "BEGINNER",
            "Growth earns growth. Time matters more than most people expect.",
            List.of("If R10,000 grew by 8% a year, after one year it would be R10,800. In year two the 8% applies to R10,800, not R10,000.",
                    "After 10 years of steady 8% growth the same R10,000 would be about R21,589. Real returns are not steady and can be negative in some years.",
                    "Starting earlier with smaller amounts can matter as much as starting later with larger ones."),
            "Run 10 years and then 20 years in the simulator with the same inputs and compare."),
        new Lesson("inflation", "Inflation and purchasing power", "BEGINNER",
            "Prices tend to rise, so the same rand buys less over time.",
            List.of("If prices rise 5% a year, something that costs R100 today would cost about R163 after 10 years.",
                    "That is why a future amount can look large but buy less. The simulator shows a value after inflation next to the nominal value.",
                    "Savings that earn less than inflation lose purchasing power even though the balance does not fall."),
            "Set inflation to 6% in the simulator and compare the two future values."),
        new Lesson("risk-and-return", "Risk and potential return", "INTERMEDIATE",
            "Higher potential return usually comes with a higher chance of loss.",
            List.of("There is no reliable way to get high returns without accepting risk. Be cautious of anyone who promises both.",
                    "Capital protection means the original amount is protected under defined conditions. Market risk means the value can fall below what you put in. Always check which one applies to a product.",
                    "Your time horizon matters: the longer you can leave money alone, the more short-term swings may matter less, but there are no guarantees."),
            "Compare the conservative, middle and higher illustrations. They are not forecasts."),
        new Lesson("diversification", "Diversification", "INTERMEDIATE",
            "Spreading money across different things reduces the damage if one of them does badly.",
            List.of("Putting everything into one share, one business or one scheme concentrates risk.",
                    "Funds that hold many assets give built-in diversification, but they still carry market risk.",
                    "Diversification reduces some risks; it does not remove the risk of loss."),
            "Think of three different places you could hold money and what could go wrong with each."),
        new Lesson("liquidity", "Liquidity: how fast can you get it?", "INTERMEDIATE",
            "Some products let you withdraw anytime. Others lock money in or charge penalties.",
            List.of("Liquidity is how quickly you can turn something into cash without losing value.",
                    "Retirement products are often designed to limit early access. Check the rules before committing.",
                    "Match liquidity to the goal: emergency money should be easy to reach."),
            "For each of your goals, ask: when will I need this money?"),
        new Lesson("fees", "Fees and their long-term effect", "INTERMEDIATE",
            "Small annual fees add up to large amounts over decades.",
            List.of("A fee is charged on the whole balance every year, whether the investment did well or not.",
                    "In the simulator, a 10% return with a 2% fee behaves like 8%. Over 20 years that difference is large.",
                    "Always ask: what are all the fees, and when are they charged?"),
            "Run the same scenario with 0% fee and 2% fee and compare the future values."),
        new Lesson("product-types", "Common product types", "INTERMEDIATE",
            "Shares, ETFs, unit trusts, money-market funds and retirement products work differently.",
            List.of("Shares are part ownership of a company; their price can move a lot.",
                    "ETFs are funds that typically track an index and trade like shares; value can fall as well as rise.",
                    "Unit trusts pool many investors' money in a managed fund; fees and risk vary by fund.",
                    "Money-market funds invest in short-term instruments and are generally lower risk than share funds, but are not the same as a bank deposit.",
                    "Retirement products usually have rules on access and contributions. Check current rules with an authorised provider or official source.",
                    "In South Africa, check that a provider or adviser is authorised by the financial sector regulator before handing over money."),
            "Pick one product type and write down its risk, fees and how quickly you could access the money."),
        new Lesson("retirement", "Planning for retirement", "ADVANCED",
            "Small regular amounts, started early, do the heavy lifting.",
            List.of("Retirement is a long horizon, so inflation and fees matter a great deal.",
                    "A useful exercise is to work backwards: what monthly income would you want, and what would that require?",
                    "The simulator can show how a monthly amount might grow. It cannot tell you whether it is enough for you."),
            "Run 30 years with a 5% yearly increase in your monthly amount to see the effect of stepping up."),
        new Lesson("spotting-scams", "Spotting investment scams", "BEGINNER",
            "If it promises guaranteed high returns, treat it as a warning sign.",
            List.of("Warning signs include guaranteed returns, pressure to act now, secrecy, and being asked to recruit others.",
                    "Check that the business and the person are authorised, and verify through an official channel you find yourself, not one the seller gives you.",
                    "Never share your PIN or one-time codes. PULA will not ask you to move money to 'unlock' returns.",
                    "If you are unsure, pause and ask someone you trust.")
            , "Read the next 'amazing opportunity' message you receive and count the warning signs.")
    );

    private static final List<Term> GLOSSARY = List.of(
        new Term("Capital", "The amount you put in."),
        new Term("Compound growth", "Growth earned on earlier growth as well as on the original amount."),
        new Term("Diversification", "Spreading money across different assets to reduce concentration risk."),
        new Term("ETF", "A fund that typically tracks an index and trades like a share."),
        new Term("Inflation", "The general rise in prices over time."),
        new Term("Liquidity", "How quickly you can turn something into cash without losing value."),
        new Term("Market risk", "The risk that an investment's value falls because of market movements."),
        new Term("Nominal value", "An amount in today's rand terms of the future, before adjusting for inflation."),
        new Term("Real value", "A future amount adjusted for inflation to show what it could buy."),
        new Term("Time horizon", "How long you can leave money invested before needing it.")
    );

    private EducationContent() {}
    public static List<Lesson> lessons() { return LESSONS; }
    public static List<Term> glossary() { return GLOSSARY; }
    public static Optional<Lesson> find(String id) { return LESSONS.stream().filter(l -> l.id().equals(id)).findFirst(); }
}
