package com.momocircle.future;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Illustrative savings/investment projection. NOT a forecast, NOT an investment account. Monthly compounding:
 * monthly rate = (1 + annualNet)^(1/12) - 1 where annualNet = annualReturn - annualFee (nominal, simple subtraction,
 * documented assumption). Contributions are made at the END of each month. Contribution growth (annual step-up) is
 * applied at the start of each year after the first. Real value = nominal / (1 + inflation)^years.
 * Uses BigDecimal (MathContext 34) with a final HALF_UP round to cents; no double arithmetic.
 */
public final class ProjectionCalculator {

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    public record Input(long startingMinor, long monthlyMinor, int years, BigDecimal annualReturnPct,
                        BigDecimal annualInflationPct, BigDecimal annualFeePct, BigDecimal contributionGrowthPct) {}

    public record Result(long totalContributionsMinor, long futureValueMinor, long growthMinor,
                         long futureValueRealMinor, BigDecimal netAnnualRatePct) {}

    private ProjectionCalculator() {}

    public static Result project(Input in) {
        if (in.startingMinor() < 0 || in.monthlyMinor() < 0) throw new IllegalArgumentException("Amounts must not be negative");
        if (in.years() < 1 || in.years() > 60) throw new IllegalArgumentException("Years must be between 1 and 60");
        BigDecimal ret = nz(in.annualReturnPct()), inf = nz(in.annualInflationPct()),
                fee = nz(in.annualFeePct()), cg = nz(in.contributionGrowthPct());
        if (ret.compareTo(BigDecimal.valueOf(-50)) < 0 || ret.compareTo(BigDecimal.valueOf(50)) > 0)
            throw new IllegalArgumentException("Return must be between -50% and 50%");
        if (inf.compareTo(BigDecimal.ZERO) < 0 || inf.compareTo(BigDecimal.valueOf(50)) > 0)
            throw new IllegalArgumentException("Inflation must be between 0% and 50%");
        if (fee.compareTo(BigDecimal.ZERO) < 0 || fee.compareTo(BigDecimal.valueOf(10)) > 0)
            throw new IllegalArgumentException("Fee must be between 0% and 10%");
        if (cg.compareTo(BigDecimal.ZERO) < 0 || cg.compareTo(BigDecimal.valueOf(30)) > 0)
            throw new IllegalArgumentException("Contribution growth must be between 0% and 30%");

        BigDecimal net = ret.subtract(fee);
        BigDecimal annualFactor = BigDecimal.ONE.add(net.movePointLeft(2), MC);
        if (annualFactor.signum() <= 0) throw new IllegalArgumentException("Net return must be above -100%");
        BigDecimal monthlyRate = nthRoot12(annualFactor).subtract(BigDecimal.ONE, MC);

        BigDecimal balance = BigDecimal.valueOf(in.startingMinor());
        BigDecimal contrib = BigDecimal.valueOf(in.monthlyMinor());
        BigDecimal totalContrib = BigDecimal.valueOf(in.startingMinor());
        BigDecimal step = BigDecimal.ONE.add(cg.movePointLeft(2), MC);
        for (int y = 0; y < in.years(); y++) {
            if (y > 0) contrib = contrib.multiply(step, MC);
            for (int m = 0; m < 12; m++) {
                balance = balance.multiply(BigDecimal.ONE.add(monthlyRate, MC), MC).add(contrib, MC);
                totalContrib = totalContrib.add(contrib, MC);
            }
        }
        long fv = balance.setScale(0, RoundingMode.HALF_UP).longValueExact();
        long tc = totalContrib.setScale(0, RoundingMode.HALF_UP).longValueExact();
        BigDecimal infFactor = BigDecimal.ONE.add(inf.movePointLeft(2), MC).pow(in.years(), MC);
        long real = balance.divide(infFactor, MC).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Result(tc, fv, fv - tc, real, net.setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** 12th root via Newton iteration in BigDecimal (no double). */
    private static BigDecimal nthRoot12(BigDecimal x) {
        BigDecimal r = BigDecimal.ONE;
        BigDecimal n = BigDecimal.valueOf(12);
        for (int i = 0; i < 200; i++) {
            BigDecimal next = r.multiply(n.subtract(BigDecimal.ONE), MC).add(x.divide(r.pow(11, MC), MC), MC).divide(n, MC);
            if (next.subtract(r, MC).abs().compareTo(new BigDecimal("1e-30")) < 0) { r = next; break; }
            r = next;
        }
        return r;
    }
}
