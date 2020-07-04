package com.meghdut.data

import org.dizitart.no2.objects.Id

data class Download(
    var statusCode: Int,
    @Id
    val id: Long,
    val name: String,
    val metaData: HashMap<String, String> = hashMapOf()
)

data class DownloadDetails(
    @Id
    val id: Long,
    val previewImage: String,
    val ratings: Int,
    val description: String,
    val catagory: String
)