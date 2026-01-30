import io.github.jbellis.jvector.graph.*
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider
import io.github.jbellis.jvector.util.Bits
import io.github.jbellis.jvector.vector.VectorSimilarityFunction
import io.github.jbellis.jvector.vector.VectorizationProvider
import io.github.jbellis.jvector.vector.types.VectorFloat
import io.github.jbellis.jvector.vector.types.VectorTypeSupport
import java.util.*

/**
 * Minimal working example for JVector 3.0.0 in Kotlin
 *
 * This demonstrates:
 * 1. Creating VectorFloat from FloatArray
 * 2. Building a JVector index
 * 3. Searching the index
 */
object JVectorExample {

    private val vts: VectorTypeSupport = VectorizationProvider.getInstance().getVectorTypeSupport()

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. Create a list of float arrays (your vectors)
        val floatArrays = listOf(
            floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f),
            floatArrayOf(2.0f, 3.0f, 4.0f, 5.0f),
            floatArrayOf(3.0f, 4.0f, 5.0f, 6.0f),
            floatArrayOf(4.0f, 5.0f, 6.0f, 7.0f),
            floatArrayOf(5.0f, 6.0f, 7.0f, 8.0f)
        )

        // 2. Convert FloatArrays to VectorFloat using VectorTypeSupport
        val vectors: ArrayList<VectorFloat<*>> = ArrayList()
        for (floatArray in floatArrays) {
            val vector = vts.createFloatVector(floatArray.size)
            for (i in floatArray.indices) {
                vector.set(i, floatArray[i])
            }
            vectors.add(vector)
        }

        // 3. Build the index
        val index = buildIndex(vectors)

        // 4. Search the index
        val queryVector = createVector(floatArrayOf(2.5f, 3.5f, 4.5f, 5.5f))
        val results = searchIndex(index, vectors, queryVector, topK = 3)

        // 5. Print results
        println("Top ${results.size} results:")
        for (nodeScore in results) {
            println("  Node ${nodeScore.node}: score = ${nodeScore.score}")
        }
    }

    /**
     * Creates a VectorFloat from a FloatArray
     */
    fun createVector(floatArray: FloatArray): VectorFloat<*> {
        val vector = vts.createFloatVector(floatArray.size)
        for (i in floatArray.indices) {
            vector.set(i, floatArray[i])
        }
        return vector
    }

    /**
     * Builds a JVector index from a list of vectors
     */
    fun buildIndex(vectors: List<VectorFloat<*>>): OnHeapGraphIndex {
        // Infer dimensionality from the first vector
        val dimension = vectors[0].length()

        // Wrap vectors in RandomAccessVectorValues
        val ravv = ListRandomAccessVectorValues(vectors, dimension)

        // Create score provider
        val bsp = BuildScoreProvider.randomAccessScoreProvider(
            ravv,
            VectorSimilarityFunction.EUCLIDEAN
        )

        // Create builder with parameters:
        // - buildScoreProvider: score provider for construction
        // - dimension: vector dimension
        // - maxDegree: graph degree (16)
        // - searchConcurrency: construction search depth (100)
        // - overflowFactor: allow degree overflow during construction (1.2f)
        // - neighborOverlap: relax neighbor diversity requirement (1.2f)
        val builder = GraphIndexBuilder(
            bsp,
            dimension,
            16,  // maxDegree
            100, // searchConcurrency
            1.2f, // overflowFactor
            1.2f  // neighborOverlap
        )

        // Build and return the index
        val index = builder.build(ravv)
        builder.close()

        return index
    }

    /**
     * Searches the index for similar vectors
     */
    fun searchIndex(
        index: OnHeapGraphIndex,
        vectors: List<VectorFloat<*>>,
        queryVector: VectorFloat<*>,
        topK: Int
    ): List<SearchResult.NodeScore> {
        // Wrap vectors in RandomAccessVectorValues for scoring
        val ravv = ListRandomAccessVectorValues(vectors, queryVector.length())

        // Perform search
        val searchResult = GraphSearcher.search(
            queryVector,
            topK,
            ravv,
            VectorSimilarityFunction.EUCLIDEAN,
            index,
            Bits.ALL
        )

        return searchResult.nodes.toList()
    }
}
