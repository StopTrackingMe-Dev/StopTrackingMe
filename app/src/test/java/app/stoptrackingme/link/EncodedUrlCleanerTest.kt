package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedUrlCleanerTest {
    private val policy = TestFixtures.builtInRule().cleaningPolicy

    @Test
    fun removesTrackingAndPreservesEncodingOrderDuplicatesAndFragment() {
        val input =
            "https://www.bilibili.com/video/BV1?spm_id_from=333&p=2&x=%2F&a=1&a=2&utm_source=%E4%B8%AD&t=42#reply"

        val result = EncodedUrlCleaner.clean(input, policy)

        assertEquals(
            "https://www.bilibili.com/video/BV1?p=2&x=%2F&a=1&a=2&t=42#reply",
            result.cleanedUrl,
        )
        assertEquals(listOf("spm_id_from", "utm_source"), result.removedParameters)
    }

    @Test
    fun forcedBusinessParameterWinsOverRemoval() {
        val expandedPolicy = policy.copy(removeExact = policy.removeExact + setOf("p", "t"))
        val input = "https://www.bilibili.com/video/BV1?p=3&t=10&utm_medium=x"

        val result = EncodedUrlCleaner.clean(input, expandedPolicy)

        assertEquals("https://www.bilibili.com/video/BV1?p=3&t=10", result.cleanedUrl)
    }

    @Test
    fun percentEncodedTrackingNameIsRecognizedButUnknownDataIsUntouched() {
        val input = "https://www.bilibili.com/video/BV1?utm%5Fsource=x&token=a%2Bb%2Fc"

        val result = EncodedUrlCleaner.clean(input, policy)

        assertEquals("https://www.bilibili.com/video/BV1?token=a%2Bb%2Fc", result.cleanedUrl)
        assertTrue(result.removedParameters.isNotEmpty())
    }

    @Test
    fun removesQuestionMarkWhenEveryParameterWasTracking() {
        val result = EncodedUrlCleaner.clean(
            "https://www.bilibili.com/video/BV1?utm_source=x#part",
            policy,
        )

        assertEquals("https://www.bilibili.com/video/BV1#part", result.cleanedUrl)
    }

    @Test
    fun cleansObservedBilibiliShareRedirectWithoutDroppingBehaviorParameters() {
        val input =
            "https://www.bilibili.com/video/BV18xKG6iErM?buvid=device&from_spmid=share.entry&is_story_h5=false&mid=sharer&p=1&plat_id=116&share_from=ugc&share_medium=android&share_plat=android&share_session_id=session&share_source=COPY&share_tag=s_i&spmid=detail.share&timestamp=123&unique_k=short&up_id=creator"

        val result = EncodedUrlCleaner.clean(input, policy)

        assertEquals(
            "https://www.bilibili.com/video/BV18xKG6iErM?p=1",
            result.cleanedUrl,
        )
    }

    @Test
    fun cleansCurrentLiveAndStoryShareParameters() {
        val liveResult = EncodedUrlCleaner.clean(
            "https://live.bilibili.com/31368705?broadcast_type=0&is_room_feed=1",
            policy,
        )
        val storyResult = EncodedUrlCleaner.clean(
            "https://www.bilibili.com/video/BV1jV5u6xEgj/?-Arouter=story&is_story_h5=true&p=1",
            policy,
        )

        assertEquals("https://live.bilibili.com/31368705", liveResult.cleanedUrl)
        assertEquals(
            "https://www.bilibili.com/video/BV1jV5u6xEgj/?p=1",
            storyResult.cleanedUrl,
        )
    }
}
