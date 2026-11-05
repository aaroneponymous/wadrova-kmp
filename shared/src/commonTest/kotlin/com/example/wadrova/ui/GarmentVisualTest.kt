package com.example.wadrova.ui

import com.example.wadrova.data.FakeGeneratedImageStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class GarmentVisualTest {
    private val item = testWardrobe.first()

    @Test
    fun theMirrorExtractionComesFirstThenTheCutoutThenThePhoto() {
        val all = item.copy(photoId = "photo", extractedImageId = "extracted", cutoutImageId = "cutout")
        assertEquals(
            listOf(StoredImage.Extracted("extracted"), StoredImage.Cutout("cutout"), StoredImage.Photo("photo")),
            all.storedImages(),
        )
        assertEquals(listOf(StoredImage.Cutout("cutout"), StoredImage.Photo("photo")), all.copy(extractedImageId = null).storedImages())
    }

    @Test
    fun anOrdinaryPhotoAndAGarmentWithoutImagesAreUnchanged() {
        assertEquals(listOf(StoredImage.Photo("photo")), item.copy(photoId = "photo").storedImages())
        assertEquals(
            listOf(StoredImage.Extracted("extracted"), StoredImage.Photo("photo")),
            item.copy(photoId = "photo", extractedImageId = "extracted").storedImages(),
        )
        assertEquals(emptyList(), item.copy(photoId = null, extractedImageId = null, cutoutImageId = null).storedImages())
    }

    @Test
    fun aMissingCutoutFileLoadsAsUnavailableSoThePhotoIsShownInstead() = runTest {
        val loader = PhotoBitmapLoader(FakePhotoStore(), FakeGeneratedImageStore())
        assertNull(loader.loadCutout("gone"))
    }

    @Test
    fun aUserGarmentCarriesItsCutoutIntoTheWardrobe() {
        val garment = com.example.wadrova.domain.UserGarment(
            Garment("user-1", GarmentCategory.TOP), "Tee", "White", null, null, false, "photo", null, "cutout",
        )
        assertEquals("cutout", garment.toWardrobeItem().cutoutImageId)
    }
}
