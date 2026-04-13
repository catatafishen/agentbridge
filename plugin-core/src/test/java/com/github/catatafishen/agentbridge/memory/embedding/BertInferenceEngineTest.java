package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(invStd, x[3], 1e-5f);
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

    // ---- selfAttention ----------------------------------------------------------

    @Test
    void selfAttentionWithAllRealTokensRunsWithoutNaN() {
        int seqLen = 2;
        float[][] hidden = makeHidden(seqLen);
        BertWeights.LayerWeights layer = makeZeroLayer();
        long[] mask = {1L, 1L};

        engine.selfAttention(hidden, seqLen, layer, mask);

        assertEquals(seqLen, hidden.length);
        assertEquals(BertWeights.HIDDEN_DIM, hidden[0].length);
        assertNoNaN(hidden, seqLen);
    }

    @Test
    void selfAttentionWithPaddingMaskRunsWithoutNaN() {
        // The third token has attentionMask = 0, so its score gets MASK_VALUE (-10000)
        // added before softmax, effectively masking it from attending.
        int seqLen = 3;
        float[][] hidden = makeHidden(seqLen);
        BertWeights.LayerWeights layer = makeZeroLayer();
        long[] mask = {1L, 1L, 0L};

        engine.selfAttention(hidden, seqLen, layer, mask);

        assertNoNaN(hidden, seqLen);
    }

    @Test
    void selfAttentionPreservesShapeForSingleToken() {
        int seqLen = 1;
        float[][] hidden = makeHidden(seqLen);
        BertWeights.LayerWeights layer = makeZeroLayer();
        long[] mask = {1L};

        engine.selfAttention(hidden, seqLen, layer, mask);

        assertEquals(1, hidden.length);
        assertEquals(BertWeights.HIDDEN_DIM, hidden[0].length);
        assertNoNaN(hidden, seqLen);
    }

    // ---- feedForward ------------------------------------------------------------

    @Test
    void feedForwardRunsWithoutNaN() {
        int seqLen = 2;
        float[][] hidden = makeHidden(seqLen);
        BertWeights.LayerWeights layer = makeZeroLayer();

        engine.feedForward(hidden, seqLen, layer);

        assertEquals(seqLen, hidden.length);
        assertEquals(BertWeights.HIDDEN_DIM, hidden[0].length);
        assertNoNaN(hidden, seqLen);
    }

    @Test
    void feedForwardOutputHasMeanNearZero() {
        // With all-zero FFN weight matrices, the output is layerNorm(residual),
        // so the mean of the output over the hidden dimension should be ≈ 0.
        int seqLen = 1;
        float[][] hidden = makeHidden(seqLen);
        BertWeights.LayerWeights layer = makeZeroLayer();

        engine.feedForward(hidden, seqLen, layer);

        float mean = 0.0f;
        for (float v : hidden[0]) mean += v;
        mean /= BertWeights.HIDDEN_DIM;
        assertEquals(0.0f, mean, 1e-4f, "feedForward output mean should be ≈ 0 after LayerNorm");
    }

    // ---- Helpers ----------------------------------------------------------------

    private static float[][] makeHidden(int seqLen) {
        float[][] h = new float[seqLen][BertWeights.HIDDEN_DIM];
        for (int i = 0; i < seqLen; i++) {
            for (int j = 0; j < BertWeights.HIDDEN_DIM; j++) {
                h[i][j] = (i + 1) * 0.001f + j * 0.0001f;
            }
        }
        return h;
    }

    /**
     * Builds a {@link BertWeights.LayerWeights} with all-zero weight matrices
     * and identity layer-norm parameters (gamma=1, beta=0).
     * Suitable for testing that the computation paths run without arithmetic errors.
     */
    private static BertWeights.LayerWeights makeZeroLayer() {
        int h = BertWeights.HIDDEN_DIM;
        int inter = BertWeights.INTERMEDIATE_DIM;
        float[] onesH = new float[h];
        java.util.Arrays.fill(onesH, 1.0f);
        float[] zerosH = new float[h];
        float[] zerosHH = new float[h * h];
        float[] zerosInter = new float[inter];
        float[] zerosInterH = new float[inter * h];
        float[] zerosHInter = new float[h * inter];
        return new BertWeights.LayerWeights(
            zerosHH, zerosH,         // query W, B
            zerosHH, zerosH,         // key W, B
            zerosHH, zerosH,         // value W, B
            zerosHH, zerosH,         // attention output W, B
            onesH, zerosH,           // attention LN gamma, beta
            zerosInterH, zerosInter, // intermediate W, B
            zerosHInter, zerosH,     // output W, B
            onesH, zerosH            // output LN gamma, beta
        );
    }

    private static void assertNoNaN(float[][] matrix, int seqLen) {
        for (int i = 0; i < seqLen; i++) {
            for (float v : matrix[i]) {
                assertFalse(Float.isNaN(v), "NaN found at row " + i);
            }
        }
    }
}
