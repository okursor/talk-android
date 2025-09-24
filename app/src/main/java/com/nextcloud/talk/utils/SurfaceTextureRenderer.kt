/*
 * SPDX-FileCopyrightText: 2024 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Surface texture renderer for MediaCodec surface-to-surface transcoding
 * with proper transformation, rotation and scaling support.
 */

package com.nextcloud.talk.utils

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a SurfaceTexture (from decoder) to an OpenGL surface (encoder input).
 * Handles scaling, rotation, and format conversion.
 */
class SurfaceTextureRenderer {

    companion object {
        private const val TAG = "SurfaceTextureRenderer"

        // Simple vertex shader for texture rendering
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = aTextureCoord.xy;
            }
        """

        // Fragment shader for external texture (SurfaceTexture)
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        private val VERTEX_COORDS = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f, // Bottom left
            1.0f, -1.0f, 0f, 1f, 0f, // Bottom right
            -1.0f, 1.0f, 0f, 0f, 1f, // Top left
            1.0f, 1.0f, 0f, 1f, 1f // Top right
        )

        private const val COORDS_PER_VERTEX = 3
        private const val TEXTURE_COORDS_PER_VERTEX = 2
        private const val VERTEX_STRIDE = (COORDS_PER_VERTEX + TEXTURE_COORDS_PER_VERTEX) * 4
    }

    private var program: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var textureCoordHandle: Int = 0
    private var textureHandle: Int = 0

    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var vertexBuffer: FloatBuffer
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val textureTransformMatrix = FloatArray(16)
    private var videoRotation = 0

    init {
        // Initialize vertex buffer
        val bb = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(VERTEX_COORDS)
        vertexBuffer.position(0)
    }

    /**
     * Setup OpenGL program and texture
     */
    fun setup(): SurfaceTexture {
        Log.d(TAG, "Setting up SurfaceTextureRenderer")

        // Load and compile shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        // Create program and link shaders
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // Check link status
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $error")
        }

        // Get handles to shader variables
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture")

        Log.d(
            TAG,
            "Shader handles - position: $positionHandle, texture: $textureCoordHandle, mvp: $mvpMatrixHandle, sampler: $textureHandle"
        )

        // Create external texture for SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        checkGlError("Texture setup")

        // Create SurfaceTexture
        surfaceTexture = SurfaceTexture(textureId)
        Log.d(TAG, "SurfaceTexture created with textureId: $textureId")

        return surfaceTexture!!
    }

    /**
     * Set viewport and projection matrix for proper aspect ratio and scaling
     */
    fun setViewport(viewWidth: Int, viewHeight: Int, textureWidth: Int, textureHeight: Int) {
        Log.d(TAG, "Setting viewport ${viewWidth}x$viewHeight for texture ${textureWidth}x$textureHeight")

        GLES20.glViewport(0, 0, viewWidth, viewHeight)

        // Calculate aspect ratios
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val textureAspect = textureWidth.toFloat() / textureHeight.toFloat()

        // Set up projection matrix to maintain aspect ratio and center the image
        Matrix.setIdentityM(projectionMatrix, 0)

        if (textureAspect > viewAspect) {
            // Texture is wider than view - fit width, letterbox height
            val scale = viewAspect / textureAspect
            Matrix.scaleM(projectionMatrix, 0, 1.0f, scale, 1.0f)
        } else {
            // Texture is taller than view - fit height, pillarbox width
            val scale = textureAspect / viewAspect
            Matrix.scaleM(projectionMatrix, 0, scale, 1.0f, 1.0f)
        }

        // Set up view matrix (identity for now)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        // Calculate MVP matrix
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        Log.d(TAG, "Viewport configured with aspect ratio correction")
    }

    /**
     * Set video rotation transformation
     * @param rotation Rotation in degrees (0, 90, 180, 270)
     */
    fun setRotation(rotation: Int) {
        videoRotation = rotation
        Log.d(TAG, "Setting video rotation to $rotation degrees")
        
        // Create rotation matrix
        Matrix.setIdentityM(rotationMatrix, 0)
        when (rotation) {
            90 -> {
                Matrix.rotateM(rotationMatrix, 0, -90f, 0f, 0f, 1f)
                Log.d(TAG, "Applied -90° rotation matrix (portrait to landscape correction)")
            }
            180 -> {
                Matrix.rotateM(rotationMatrix, 0, -180f, 0f, 0f, 1f)
                Log.d(TAG, "Applied -180° rotation matrix")
            }
            270 -> {
                Matrix.rotateM(rotationMatrix, 0, -270f, 0f, 0f, 1f)
                Log.d(TAG, "Applied -270° rotation matrix (portrait to landscape correction)")
            }
            else -> {
                Log.d(TAG, "No rotation applied (0°)")
            }
        }
        
        // Update MVP matrix with rotation
        updateMvpMatrix()
    }
    
    /**
     * Update MVP matrix combining projection, view, and rotation
     */
    private fun updateMvpMatrix() {
        val tempMatrix = FloatArray(16)
        
        // Combine view and rotation matrices
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
        
        // Combine with projection
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)
        
        Log.d(TAG, "MVP matrix updated with rotation: $videoRotation°")
    }

    /**
     * Render the current frame from SurfaceTexture
     */
    fun drawFrame() {
        surfaceTexture?.updateTexImage() ?: return

        // Clear the screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Use our shader program
        GLES20.glUseProgram(program)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // Set MVP matrix
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Set vertex attributes
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE,
            vertexBuffer
        )

        vertexBuffer.position(COORDS_PER_VERTEX)
        GLES20.glEnableVertexAttribArray(textureCoordHandle)
        GLES20.glVertexAttribPointer(
            textureCoordHandle,
            TEXTURE_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE,
            vertexBuffer
        )

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordHandle)

        checkGlError("drawFrame")
    }

    /**
     * Get the transformation matrix timestamp
     */
    fun getTimestamp(): Long = surfaceTexture?.timestamp ?: 0

    /**
     * Release all resources
     */
    fun release() {
        Log.d(TAG, "Releasing SurfaceTextureRenderer")

        surfaceTexture?.release()
        surfaceTexture = null

        if (textureId != 0) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
        }

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $error")
        }

        return shader
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$operation: glError $error")
            throw RuntimeException("$operation: glError $error")
        }
    }
}
