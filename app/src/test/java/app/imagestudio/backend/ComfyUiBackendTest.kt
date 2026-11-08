package app.imagestudio.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ComfyUiBackendTest {
    private lateinit var server: MockWebServer
    private val png = byteArrayOf(9, 8, 7)
    private val queued = mutableListOf<String>()
    private var historyCalls = 0
    private var failRun = false

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path.startsWith("/ws") -> MockResponse().setResponseCode(404)
                    path == "/object_info/CheckpointLoaderSimple" -> MockResponse().setBody(
                        """{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["sd_xl_base_1.0.safetensors","dreamshaper_8.safetensors"],{}]}}}}"""
                    )
                    path == "/upload/image" -> MockResponse().setBody("""{"name":"imagestudio-src.png","subfolder":"","type":"input"}""")
                    path == "/prompt" -> { queued += request.body.readUtf8(); MockResponse().setBody("""{"prompt_id":"p1","number":1}""") }
                    path == "/history/p1" -> {
                        historyCalls++
                        when {
                            historyCalls < 2 -> MockResponse().setBody("{}")
                            failRun -> MockResponse().setBody("""{"p1":{"outputs":{},"status":{"status_str":"error","completed":false,
                                "messages":[["execution_start",{}],["execution_error",{"node_type":"KSampler","exception_message":"CUDA out of memory"}]]}}}""")
                            else -> MockResponse().setBody("""{"p1":{"outputs":{"9":{"images":[{"filename":"ImageStudio_0001.png","subfolder":"","type":"output"},
                                {"filename":"ImageStudio_0002.png","subfolder":"","type":"output"}]}},"status":{"status_str":"success","completed":true}}}""")
                        }
                    }
                    path.startsWith("/view?") -> MockResponse().setBody(Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }
    private fun url() = server.url("/").toString()

    @Test fun builtInTextToImageQueuesPatchedWorkflowAndDownloadsOutputs() = runBlocking<Unit> {
        val b = ComfyUiBackend(url())
        val out = b.generate(GenerationRequest("a castle", negativePrompt = "ugly", seed = 77, steps = 30, count = 2, aspectRatio = AspectRatio.LANDSCAPE_16_9))
        assertEquals(2, out.size)
        assertArrayEquals(png, out[0].bytes)
        assertEquals(77L, out[0].seed)
        val prompt = kotlinx.serialization.json.Json.parseToJsonElement(queued.single()).jsonObject["prompt"]!!.jsonObject
        fun input(node: String, key: String) = prompt[node]!!.jsonObject["inputs"]!!.jsonObject[key]!!.jsonPrimitive.content
        assertEquals("a castle", input("6", "text"))
        assertEquals("ugly", input("7", "text"))
        assertEquals("77", input("3", "seed"))
        assertEquals("30", input("3", "steps"))
        assertEquals("sd_xl_base_1.0.safetensors", input("4", "ckpt_name")) // first server checkpoint
        assertEquals("2", input("5", "batch_size"))
        val w = input("5", "width").toInt(); val h = input("5", "height").toInt()
        assertTrue("16:9 from 1024² area: ${w}x$h", w > h && w % 64 == 0 && h % 64 == 0)
    }

    @Test fun customWorkflowFollowsSamplerLinksAndKeepsCustomNodes() = runBlocking<Unit> {
        val wf = """
        {"1":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"mine.safetensors"}},
         "2":{"class_type":"LoraLoader","inputs":{"lora_name":"style.safetensors","strength_model":0.8,"model":["1",0],"clip":["1",1]}},
         "20":{"class_type":"CLIPTextEncode","inputs":{"text":"old negative","clip":["2",1]}},
         "10":{"class_type":"CLIPTextEncode","inputs":{"text":"old positive","clip":["2",1]}},
         "5":{"class_type":"EmptyLatentImage","inputs":{"width":512,"height":512,"batch_size":1}},
         "3":{"class_type":"KSampler","inputs":{"seed":1,"steps":20,"model":["2",0],"positive":["10",0],"negative":["20",0],"latent_image":["5",0]}},
         "8":{"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["1",2]}},
         "9":{"class_type":"SaveImage","inputs":{"images":["8",0]}}}
        """.trimIndent()
        val b = ComfyUiBackend(url(), customWorkflow = wf, customWorkflowName = "My LoRA flow")
        assertEquals("ComfyUI · My LoRA flow", b.displayName)
        b.generate(GenerationRequest("a dog", negativePrompt = "", seed = 5, model = ComfyUiBackend.WORKFLOW_DEFAULT))
        val prompt = kotlinx.serialization.json.Json.parseToJsonElement(queued.single()).jsonObject["prompt"]!!.jsonObject
        fun input(node: String, key: String) = prompt[node]!!.jsonObject["inputs"]!!.jsonObject[key]!!.jsonPrimitive.content
        assertEquals("a dog", input("10", "text"))
        assertEquals("old negative", input("20", "text")) // blank negative keeps the workflow's
        assertEquals("mine.safetensors", input("1", "ckpt_name"))
        assertEquals("style.safetensors", input("2", "lora_name"))
        assertEquals("512", input("5", "width"))
    }

    @Test fun uiFormatWorkflowIsRejectedWithHelp() {
        try { ComfyWorkflows.parse("""{"nodes":[],"links":[]}"""); fail() }
        catch (e: BackendException) { assertTrue(e.message!!.contains("Export (API)")) }
    }

    @Test fun executionErrorIsReported() = runBlocking<Unit> {
        failRun = true
        try { ComfyUiBackend(url()).generate(GenerationRequest("x")); fail() }
        catch (e: BackendException) { assertTrue(e.message!!, e.message!!.contains("CUDA out of memory")) }
    }

    @Test fun inpaintUploadsImageAndMask() = runBlocking<Unit> {
        ComfyUiBackend(url()).generate(GenerationRequest("hat", mode = GenerationMode.Inpaint(png, png)))
        val prompt = kotlinx.serialization.json.Json.parseToJsonElement(queued.single()).jsonObject["prompt"]!!.jsonObject
        assertEquals("VAEEncodeForInpaint", prompt["11"]!!.jsonObject["class_type"]!!.jsonPrimitive.content)
    }

    @Test fun fetchCheckpoints() = runBlocking<Unit> {
        assertEquals(listOf("sd_xl_base_1.0.safetensors", "dreamshaper_8.safetensors"), ComfyUiBackend(url()).fetchCheckpoints())
    }
}
