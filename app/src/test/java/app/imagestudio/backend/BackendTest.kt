package app.imagestudio.backend

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class BackendTest {
    private lateinit var server: MockWebServer
    private val png = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun imageResponse(seed: Long) =
        MockResponse().setBody(Buffer().write(png)).setHeader("seed", seed).setHeader("finish-reason", "SUCCESS")

    @Test fun stabilityTextToImageUsesCoreWithStylePreset() = runTest {
        server.enqueue(imageResponse(42))
        val b = StabilityBackend("sk-test", base())
        val out = b.generate(GenerationRequest("a red fox", negativePrompt = "blurry", style = StylePreset.ANIME, aspectRatio = AspectRatio.LANDSCAPE_16_9, seed = 42))
        assertEquals(1, out.size)
        assertArrayEquals(png, out[0].bytes)
        assertEquals(42L, out[0].seed)
        val req = server.takeRequest()
        assertEquals("/v2beta/stable-image/generate/core", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"style_preset\"") && body.contains("anime"))
        assertTrue(body.contains("16:9"))
        assertTrue(body.contains("blurry"))
    }

    @Test fun stabilityBatchUsesSuccessiveSeeds() = runTest {
        repeat(3) { server.enqueue(imageResponse(100L + it)) }
        val out = StabilityBackend("k", base()).generate(GenerationRequest("x", seed = 100, count = 3))
        assertEquals(listOf(100L, 101L, 102L), out.map { it.seed })
        val seeds = (0 until 3).map {
            Regex("""name="seed"\r?\n(?:[^\r\n]+\r?\n)*\r?\n(\d+)""").find(server.takeRequest().body.readUtf8())!!.groupValues[1]
        }
        assertEquals(listOf("100", "101", "102"), seeds)
    }

    @Test fun stabilityImageToImageRoutesCoreToSd3() = runTest {
        server.enqueue(imageResponse(1))
        StabilityBackend("k", base()).generate(GenerationRequest("x", style = StylePreset.ANIME, mode = GenerationMode.ImageToImage(png, 0.4f)))
        val req = server.takeRequest()
        assertEquals("/v2beta/stable-image/generate/sd3", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("image-to-image"))
        assertTrue(body.contains("0.4"))
        assertFalse("sd3 has no style_preset", body.contains("style_preset"))
        assertTrue(body.contains("anime illustration"))
    }

    @Test fun stabilityInpaint() = runTest {
        server.enqueue(imageResponse(1))
        StabilityBackend("k", base()).generate(GenerationRequest("a hat", mode = GenerationMode.Inpaint(png, png)))
        val req = server.takeRequest()
        assertEquals("/v2beta/stable-image/edit/inpaint", req.path)
        assertTrue(req.body.readUtf8().contains("name=\"mask\""))
    }

    @Test fun stabilityErrorIsReadable() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"name":"payment_required","errors":["You lack sufficient credits"]}"""))
        try {
            StabilityBackend("k", base()).generate(GenerationRequest("x"))
            fail()
        } catch (e: BackendException) {
            assertTrue(e.message!!, e.message!!.startsWith("Out of credits"))
            assertTrue(e.message!!.contains("sufficient credits"))
        }
    }

    @Test fun missingKeyFailsFast() = runTest {
        val b = StabilityBackend("", base())
        assertFalse(b.isConfigured)
        try { b.generate(GenerationRequest("x")); fail() } catch (_: BackendException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun openAiTextToImage() = runTest {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setBody("""{"created":1,"data":[{"b64_json":"$b64"},{"b64_json":"$b64"}]}"""))
        val out = OpenAiBackend("sk-o", { i, _ -> i }, base()).generate(GenerationRequest("a cat", count = 2, aspectRatio = AspectRatio.PORTRAIT_2_3, style = StylePreset.WATERCOLOR))
        assertEquals(2, out.size)
        assertArrayEquals(png, out[0].bytes)
        val req = server.takeRequest()
        assertEquals("/v1/images/generations", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"size\":\"1024x1536\""))
        assertTrue(body.contains("watercolor painting"))
        assertTrue(body.contains("\"n\":2"))
    }

    @Test fun openAiInpaintConvertsMask() = runTest {
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$b64"}]}"""))
        var converted = false
        OpenAiBackend("k", { i, _ -> converted = true; i }, base()).generate(GenerationRequest("x", mode = GenerationMode.Inpaint(png, png)))
        assertTrue(converted)
        val req = server.takeRequest()
        assertEquals("/v1/images/edits", req.path)
        assertTrue(req.body.readUtf8().contains("name=\"mask\""))
    }
}
