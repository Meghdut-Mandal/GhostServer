package com.meghdut

import com.google.gson.Gson
import com.meghdut.data.ChatClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random
import kotlin.test.Test

class ApplicationTest {

    val okHttpClient by lazy { OkHttpClient.Builder().build() }
    val url = "http://localhost:8081"
    val addServerUrl = "$url/admin/instances/add"
    val getServersUrl = "$url/admin/instances/list"
    val startMeeting = "$url/admin/new_meeting"
    val doneMeeting="$url/admin/intances/done"


    @Test
    fun a_testAdd() {
        for (i in  2.. 10){
            addServerToPool(i)
        }
        for (i in  2.. 13) {
            startMeeting()
        }

        for (i in  2.. 10) {
            doneMeeting(i)
        }

        for (i in  2.. 5) {
            startMeeting()
        }
    }





    private fun doneMeeting(i: Int) {
        val request = Request.Builder().url(doneMeeting + "?id=Server$i").get().build()
        val response = okHttpClient.newCall(request).execute()
        println("com.meghdut>ApplicationTest>testAddClient  $i  ${response.body?.string()} ")
        response.close()
    }

    @Test
     fun startMeeting() {
        val request = Request.Builder().url(startMeeting).get().build()
        val response = okHttpClient.newCall(request).execute()
        println("com.meghdut>ApplicationTest>testAddClient    ${response.body?.string()} ")
        response.close()
    }

    private fun addServerToPool(port: Int) {
        val client = ChatClient("Server$port", "http://localhost:${port + 8080}", false)
        val body = Gson().toJson(client).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(addServerUrl).post(body).build()
        val response = okHttpClient.newCall(request).execute()
        println("com.meghdut>ApplicationTest>testAddClient ${response.body?.string()} ")
        response.close()
    }


    @Test
    fun randTest(){
        repeat(100){
            println("com.meghdut>ApplicationTest>randTest  test number $it ")
            val id=Random.nextInt(3)
            when(id){
                0->{
                    addServerToPool(id)
                }
                1->{
                    startMeeting()
                }
                2->{
                    doneMeeting(id)
                }

            }

        }
    }


    @Test
    fun add4(){
        for (i in 2..5){
            addServerToPool(i)
        }
    }



}
