/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022-2024 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2023 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.upload.normal

import android.content.Context
import android.net.Uri
import android.util.Log
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.exception.HttpException
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.dagger.modules.RestModule
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.jobs.ShareOperationWorker
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.FileUtils
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * RequestBody wrapper that reports upload progress
 */
private class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val progressListener: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val fileLength = file.length()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val inputStream = file.inputStream()
        var uploaded = 0L

        inputStream.use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                uploaded += read
                sink.write(buffer, 0, read)

                // Report progress
                progressListener(uploaded, fileLength)
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
    }
}

class FileUploader(
    okHttpClient: OkHttpClient,
    val context: Context,
    val currentUser: User,
    val roomToken: String,
    val ncApi: NcApi,
    val file: File
) {

    private var okHttpClientNoRedirects: OkHttpClient? = null
    private var okhttpClient: OkHttpClient = okHttpClient

    init {
        initHttpClient(okHttpClient, currentUser)
    }

    fun upload(
        sourceFileUri: Uri,
        fileName: String,
        remotePath: String,
        metaData: String?,
        progressListener: ((Int) -> Unit)? = null
    ): Observable<Boolean> =
        ncApi.uploadFile(
            ApiUtils.getCredentials(
                currentUser.username,
                currentUser.token
            ),
            ApiUtils.getUrlForFileUpload(
                currentUser.baseUrl!!,
                currentUser.userId!!,
                remotePath
            ),
            createRequestBody(sourceFileUri, progressListener)
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .flatMap { response ->
                if (response.isSuccessful) {
                    ShareOperationWorker.shareFile(
                        roomToken,
                        currentUser,
                        remotePath,
                        metaData
                    )
                    FileUtils.copyFileToCache(context, sourceFileUri, fileName)
                    Observable.just(true)
                } else {
                    if (response.code() == HTTP_CODE_NOT_FOUND ||
                        response.code() == HTTP_CODE_CONFLICT
                    ) {
                        createDavResource(sourceFileUri, fileName, remotePath, metaData, progressListener)
                    } else {
                        Observable.just(false)
                    }
                }
            }

    private fun createDavResource(
        sourceFileUri: Uri,
        fileName: String,
        remotePath: String,
        metaData: String?,
        progressListener: ((Int) -> Unit)? = null
    ): Observable<Boolean> =
        Observable.fromCallable {
            val userFileUploadPath = ApiUtils.userFileUploadPath(
                currentUser.baseUrl!!,
                currentUser.userId!!
            )
            val userTalkAttachmentsUploadPath = ApiUtils.userTalkAttachmentsUploadPath(
                currentUser.baseUrl!!,
                currentUser.userId!!
            )

            var davResource = DavResource(
                okHttpClientNoRedirects!!,
                userFileUploadPath.toHttpUrlOrNull()!!
            )
            createFolder(davResource)
            initHttpClient(okHttpClient = okhttpClient, currentUser)
            davResource = DavResource(
                okHttpClientNoRedirects!!,
                userTalkAttachmentsUploadPath.toHttpUrlOrNull()!!
            )
            createFolder(davResource)
            true
        }
            .subscribeOn(Schedulers.io())
            .flatMap { upload(sourceFileUri, fileName, remotePath, metaData, progressListener) }

    @Suppress("Detekt.TooGenericExceptionCaught")
    private fun createRequestBody(
        sourceFileUri: Uri,
        progressListener: ((Int) -> Unit)? = null
    ): RequestBody? {
        return try {
            val contentType = "application/octet-stream".toMediaTypeOrNull()
            
            if (progressListener != null) {
                // Use ProgressRequestBody for real upload progress
                ProgressRequestBody(file, contentType) { bytesWritten, totalBytes ->
                    val percentage = if (totalBytes > 0) {
                        (bytesWritten * 100 / totalBytes).toInt()
                    } else {
                        0
                    }
                    progressListener(percentage)
                }
            } else {
                // Fallback: original implementation without progress
                var requestBody: RequestBody? = null
                val input: InputStream = context.contentResolver.openInputStream(sourceFileUri)!!
                input.use {
                    val buf = ByteArray(input.available())
                    while (it.read(buf) != -1) {
                        requestBody = RequestBody.create(contentType, buf)
                    }
                }
                requestBody
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to create RequestBody for $sourceFileUri", e)
            null
        }
    }

    private fun initHttpClient(okHttpClient: OkHttpClient, currentUser: User) {
        val okHttpClientBuilder: OkHttpClient.Builder = okHttpClient.newBuilder()
        okHttpClientBuilder.followRedirects(false)
        okHttpClientBuilder.followSslRedirects(false)
        okHttpClientBuilder.protocols(listOf(Protocol.HTTP_1_1))
        okHttpClientBuilder.authenticator(
            RestModule.HttpAuthenticator(
                ApiUtils.getCredentials(
                    currentUser.username,
                    currentUser.token
                )!!,
                "Authorization"
            )
        )
        this.okHttpClientNoRedirects = okHttpClientBuilder.build()
    }

    @Suppress("Detekt.ThrowsCount")
    private fun createFolder(davResource: DavResource) {
        try {
            davResource.mkCol(
                xmlBody = null
            ) { response: Response ->

                if (!response.isSuccessful) {
                    throw IOException("failed to create folder. response code: " + response.code)
                }
            }
        } catch (e: IOException) {
            throw IOException("failed to create folder", e)
        } catch (e: HttpException) {
            if (e.code == METHOD_NOT_ALLOWED_CODE) {
                Log.d(TAG, "Folder most probably already exists, that's okay, just continue..")
            } else {
                throw IOException("failed to create folder", e)
            }
        }
    }

    companion object {
        private val TAG = FileUploader::class.simpleName
        private const val METHOD_NOT_ALLOWED_CODE: Int = 405
        private const val HTTP_CODE_NOT_FOUND: Int = 404
        private const val HTTP_CODE_CONFLICT: Int = 409
    }
}
