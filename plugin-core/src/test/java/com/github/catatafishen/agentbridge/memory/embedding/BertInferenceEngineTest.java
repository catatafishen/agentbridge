package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Tests for the package-private primitive operations in {@link BertInferenceEngine}:
 * {@code layerNorm}, {@code linear}, {@code gelu}, and {@code softmax}.
 *
 * <p>These are pure-math tests — the engine is constructed with a dummy
 * {@link BertWeights} because the primitive ops never access the weights field.
 */
class BertInferenceEngineTest {

    private static BertInferenceEngine engine;

    @BeforeAll
    static void setUp() {
        // The primitive ops under test never access the weights field,
        // so a mock is sufficient to satisfy the @NotNull constructor parameter.
        engine = new BertInferenceEngine(mock(BertWeights.class));
    }

    // ---- layerNorm --------------------------------------------------------------

    @Test
    void layerNormZeroMeanUnitVariance() {
        float[] x = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        float[] gamma = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        float[] beta = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

        engine.layerNorm(x, 5, gamma, beta);

        // Output should have mean ≈ 0
        float mean = 0.0f;
        for (float v : x) {
            mean += v;
        }
        mean /= x.length;
        assertEquals(0.0f, mean, 1e-5f, "mean should be ≈ 0");

        // Output should have variance ≈ 1
        float variance = 0.0f;
        for (float v : x) {
            variance += v * v;
        }
        variance /= x.length;
        assertEquals(1.0f, variance, 1e-5f, "variance should be ≈ 1");

        // Verify specific known values:
        // input [1,2,3,4,5] → mean=3, var=2, invStd = 1/√(2 + 1e-12) ≈ 0.7071
        float invStd = (float) (1.0 / Math.sqrt(2.0 + 1e-12));
        assertEquals(-2.0f * invStd, x[0], 1e-5f);
        assertEquals(-1.0f * invStd, x[1], 1e-5f);
        assertEquals(0.0f, x[2], 1e-5f);
        assertEquals(1.0f * invStd, x[3], 1e-5f);
        assertEquals(2.0f * invStd, x[4], 1e-5f);
    }

    @Test
    void layerNormScalesAndShifts() {
        float[] x = {0.0f, 0.0f, 0.0f};
        float[] gamma = {2.0f, 2.0f, 2.0f};
        float[] beta = {1.0f, 1.0f, 1.0f};

        engine.layerNorm(x, 3, gamma, beta);

        // Constant input: (x − mean) = 0 for all elements,
        // so result = 0 × invStd × gamma + beta = beta
        assertEquals(1.0f, x[0], 1e-5f);
        assertEquals(1.0f, x[1], 1e-5f);
        assertEquals(1.0f, x[2], 1e-5f);
    }

    // ---- linear -----------------------------------------------------------------

    @Test
    void linearMultipliesAndAdds() {
        // 2×2 identity weight, bias [10, 20], input [3, 5]
        float[] input = {3.0f, 5.0f};
        float[] weight = {1.0f, 0.0f, 0.0f, 1.0f}; // row-major: row0=[1,0], row1=[0,1]
        float[] bias = {10.0f, 20.0f};

        float[] result = engine.linear(input, 2, weight, bias, 2);

        assertEquals(13.0f, result[0], 1e-6f);
        assertEquals(25.0f, result[1], 1e-6f);
    }

    @Test
    void linearNonSquareMatrix() {
        // 3→2 transformation: pick first two components
        float[] input = {1.0f, 2.0f, 3.0f};
        float[] weight = {
            1.0f, 0.0f, 0.0f, // row 0 (inDim=3): selects input[0]
            0.0f, 1.0f, 0.0f  // row 1 (inDim=3): selects input[1]
        };
        float[] bias = {0.0f, 0.0f};

        float[] result = engine.linear(input, 3, weight, bias, 2);

        assertEquals(1.0f, result[0], 1e-6f);
        assertEquals(2.0f, result[1], 1e-6f);
    }

    // ---- gelu -------------------------------------------------------------------

    @Test
    void geluApproximation() {
        float[] x = {0.0f, 1.0f, -1.0f, 3.0f};

        engine.gelu(x, 4);

        assertEquals(0.0f, x[0], 1e-3f, "gelu(0) should be 0");
        assertEquals(0.8413f, x[1], 1e-3f, "gelu(1) should be ≈ 0.8413");
        assertEquals(-0.1587f, x[2], 1e-3f, "gelu(-1) should be ≈ -0.1587");
        assertEquals(2.9960f, x[3], 1e-3f, "gelu(3) should be ≈ 2.9960");
    }

    // ---- softmax ----------------------------------------------------------------

    @Test
    void softmaxSumsToOne() {
        float[] x = {1.0f, 2.0f, 3.0f, 100.0f, 200.0f};

        engine.softmax(x, 5);

        float sum = 0.0f;
        for (float v : x) {
            sum += v;
        }
        assertEquals(1.0f, sum, 1e-6f, "softmax output should sum to 1.0");

        // The max element (originally 200) should dominate the distribution
        assertEquals(1.0f, x[4], 1e-6f, "softmax of dominant element should be ≈ 1.0");
    }

    @Test
    void softmaxSlice() {
        // softmax(x, 3) processes only x[0..2], leaving x[3] and x[4] unchanged.
        // (The actual API has no offset parameter — it always starts at index 0.)
        float[] x = {1.0f, 2.0f, 3.0f, 99.0f, 99.0f};

        engine.softmax(x, 3);

        // Elements beyond len should be unchanged
        assertEquals(99.0f, x[3], 0.0f, "element outside softmax range must be unchanged");
        assertEquals(99.0f, x[4], 0.0f, "element outside softmax range must be unchanged");

        // First 3 elements should sum to ≈ 1.0
        float sum = x[0] + x[1] + x[2];
        assertEquals(1.0f, sum, 1e-6f, "softmax of first 3 elements should sum to 1.0");
    }

    @Test
    void softmaxUniformInput() {
        float[] x = {5.0f, 5.0f, 5.0f};

        engine.softmax(x, 3);

        float expected = 1.0f / 3.0f;
        assertEquals(expected, x[0], 1e-6f);
        assertEquals(expected, x[1], 1e-6f);
        assertEquals(expected, x[2], 1e-6f);
    }
}
