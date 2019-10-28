package org.telegram.messenger.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import com.google.android.exoplayer2.util.Log;

import org.telegram.messenger.AudioTranscoder;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.nio.ByteBuffer;

public class MediaCodecVideoConvertor {

    MP4Builder mediaMuxer;
    MediaExtractor extractor;

    MediaController.VideoConvertorListener callback;

    private final static int PROCESSOR_TYPE_OTHER = 0;
    private final static int PROCESSOR_TYPE_QCOM = 1;
    private final static int PROCESSOR_TYPE_INTEL = 2;
    private final static int PROCESSOR_TYPE_MTK = 3;
    private final static int PROCESSOR_TYPE_SEC = 4;
    private final static int PROCESSOR_TYPE_TI = 5;

    private static final int MEDIACODEC_TIMEOUT_DEFAULT = 2500;
    private static final int MEDIACODEC_TIMEOUT_INCREASED = 22000;

    public boolean convertVideo(String videoPath, File cacheFile,
                                int rotationValue, boolean isSecret,
                                int resultWidth, int resultHeight,
                                int framerate, int bitrate,
                                long startTime, long endTime,
                                boolean needCompress,
                                MediaController.VideoConvertorListener callback){
        this.callback = callback;
        boolean error = false;

        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Mp4Movie movie = new Mp4Movie();
            movie.setCacheFile(cacheFile);
            movie.setRotation(rotationValue);
            movie.setSize(resultWidth, resultHeight);
            mediaMuxer = new MP4Builder().createMovie(movie, isSecret);
            extractor = new MediaExtractor();
            extractor.setDataSource(videoPath);



            checkConversionCanceled();

            if (needCompress) {
                int videoIndex = MediaController.findTrack(extractor, false);
                int audioIndex = bitrate != -1 ? MediaController.findTrack(extractor, true) : -1;

                AudioTranscoder audioTranscoder = null;
                ByteBuffer audioBuffer = null;
                boolean copyAudioBuffer = true;

                if (videoIndex >= 0) {
                    MediaCodec decoder = null;
                    MediaCodec encoder = null;
                    InputSurface inputSurface = null;
                    OutputSurface outputSurface = null;

                    try {
                        long videoTime = -1;
                        boolean outputDone = false;
                        boolean inputDone = false;
                        boolean decoderDone = false;
                        int swapUV = 0;
                        int videoTrackIndex = -5;
                        int audioTrackIndex = -5;

                        int colorFormat;
                        int processorType = PROCESSOR_TYPE_OTHER;
                        String manufacturer = Build.MANUFACTURER.toLowerCase();
                        if (Build.VERSION.SDK_INT < 18) {
                            MediaCodecInfo codecInfo = MediaController.selectCodec(MediaController.VIDEO_MIME_TYPE);
                            colorFormat = MediaController.selectColorFormat(codecInfo, MediaController.VIDEO_MIME_TYPE);
                            if (colorFormat == 0) {
                                throw new RuntimeException("no supported color format");
                            }
                            String codecName = codecInfo.getName();
                            if (codecName.contains("OMX.qcom.")) {
                                processorType = PROCESSOR_TYPE_QCOM;
                                if (Build.VERSION.SDK_INT == 16) {
                                    if (manufacturer.equals("lge") || manufacturer.equals("nokia")) {
                                        swapUV = 1;
                                    }
                                }
                            } else if (codecName.contains("OMX.Intel.")) {
                                processorType = PROCESSOR_TYPE_INTEL;
                            } else if (codecName.equals("OMX.MTK.VIDEO.ENCODER.AVC")) {
                                processorType = PROCESSOR_TYPE_MTK;
                            } else if (codecName.equals("OMX.SEC.AVC.Encoder")) {
                                processorType = PROCESSOR_TYPE_SEC;
                                swapUV = 1;
                            } else if (codecName.equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
                                processorType = PROCESSOR_TYPE_TI;
                            }
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("codec = " + codecInfo.getName() + " manufacturer = " + manufacturer + "device = " + Build.MODEL);
                            }
                        } else {
                            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("colorFormat = " + colorFormat);
                        }

                        int resultHeightAligned = resultHeight;
                        int padding = 0;
                        int bufferSize = resultWidth * resultHeight * 3 / 2;
                        if (processorType == PROCESSOR_TYPE_OTHER) {
                            if (resultHeight % 16 != 0) {
                                resultHeightAligned += (16 - (resultHeight % 16));
                                padding = resultWidth * (resultHeightAligned - resultHeight);
                                bufferSize += padding * 5 / 4;
                            }
                        } else if (processorType == PROCESSOR_TYPE_QCOM) {
                            if (!manufacturer.toLowerCase().equals("lge")) {
                                int uvoffset = (resultWidth * resultHeight + 2047) & ~2047;
                                padding = uvoffset - (resultWidth * resultHeight);
                                bufferSize += padding;
                            }
                        } else if (processorType == PROCESSOR_TYPE_TI) {
                            //resultHeightAligned = 368;
                            //bufferSize = resultWidth * resultHeightAligned * 3 / 2;
                            //resultHeightAligned += (16 - (resultHeight % 16));
                            //padding = resultWidth * (resultHeightAligned - resultHeight);
                            //bufferSize += padding * 5 / 4;
                        } else if (processorType == PROCESSOR_TYPE_MTK) {
                            if (manufacturer.equals("baidu")) {
                                resultHeightAligned += (16 - (resultHeight % 16));
                                padding = resultWidth * (resultHeightAligned - resultHeight);
                                bufferSize += padding * 5 / 4;
                            }
                        }

                        extractor.selectTrack(videoIndex);
                        MediaFormat videoFormat = extractor.getTrackFormat(videoIndex);

                        if (startTime > 0) {
                            extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        } else {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        }

                        MediaFormat outputFormat = MediaFormat.createVideoFormat(MediaController.VIDEO_MIME_TYPE, resultWidth, resultHeight);
                        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
                        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

                        if (Build.VERSION.SDK_INT >= 23) {
                            int profile;
                            int level;

                            if (Math.min(resultHeight, resultWidth) >= 1080) {
                                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                                level = MediaCodecInfo.CodecProfileLevel.AVCLevel41;
                            } else if (Math.min(resultHeight, resultWidth) >= 720) {
                                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                                level = MediaCodecInfo.CodecProfileLevel.AVCLevel4;
                            } else if (Math.min(resultHeight, resultWidth) >= 480) {
                                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
                                level = MediaCodecInfo.CodecProfileLevel.AVCLevel31;
                            } else {
                                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
                                level = MediaCodecInfo.CodecProfileLevel.AVCLevel3;
                            }

                            MediaCodecInfo.CodecCapabilities capabilities = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(MediaController.VIDEO_MIME_TYPE, profile, level);

                            if (capabilities == null && profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
                                capabilities = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(MediaController.VIDEO_MIME_TYPE, profile, level);
                            }
                            if (capabilities.getEncoderCapabilities() != null) {
                                outputFormat.setInteger(MediaFormat.KEY_PROFILE, profile);
                                outputFormat.setInteger(MediaFormat.KEY_LEVEL, level);

                                int maxBitrate = capabilities.getVideoCapabilities().getBitrateRange().getUpper();
                                if (bitrate > maxBitrate) {
                                    bitrate = maxBitrate;
                                    outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                                }

                                int maxFramerate = capabilities.getVideoCapabilities().getSupportedFrameRates().getUpper();
                                if (framerate > maxFramerate) {
                                    framerate = maxFramerate;
                                    outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
                                }
                            }
                        } else {
                            if (Math.min(resultHeight, resultWidth) <= 480) {
                                if (bitrate > 921600) bitrate = 921600;
                                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                            }
                        }

                        if (Build.VERSION.SDK_INT < 18) {
                            outputFormat.setInteger("stride", resultWidth + 32);
                            outputFormat.setInteger("slice-height", resultHeight);
                        }

                        encoder = MediaCodec.createEncoderByType(MediaController.VIDEO_MIME_TYPE);
                        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        if (Build.VERSION.SDK_INT >= 18) {
                            inputSurface = new InputSurface(encoder.createInputSurface());
                            inputSurface.makeCurrent();
                        }
                        encoder.start();

                        decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
                        if (Build.VERSION.SDK_INT >= 18) {
                            outputSurface = new OutputSurface();
                        } else {
                            outputSurface = new OutputSurface(resultWidth, resultHeight, rotationValue,false);
                        }
                        decoder.configure(videoFormat, outputSurface.getSurface(), null, 0);
                        decoder.start();

                        ByteBuffer[] decoderInputBuffers = null;
                        ByteBuffer[] encoderOutputBuffers = null;
                        ByteBuffer[] encoderInputBuffers = null;
                        if (Build.VERSION.SDK_INT < 21) {
                            decoderInputBuffers = decoder.getInputBuffers();
                            encoderOutputBuffers = encoder.getOutputBuffers();
                            if (Build.VERSION.SDK_INT < 18) {
                                encoderInputBuffers = encoder.getInputBuffers();
                            }
                        }

                        if (audioIndex >= 0) {
                            MediaFormat audioFormat = extractor.getTrackFormat(audioIndex);
                            copyAudioBuffer = audioFormat.getString(MediaFormat.KEY_MIME).equals(MediaController.AUIDO_MIME_TYPE);
                            audioTrackIndex = mediaMuxer.addTrack(audioFormat, true);

                            if (copyAudioBuffer) {
                                extractor.selectTrack(audioIndex);
                                int maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                                audioBuffer = ByteBuffer.allocateDirect(maxBufferSize);
                            } else {

                                MediaExtractor audioExtractor = new MediaExtractor();
                                audioExtractor.setDataSource(videoPath);
                                audioExtractor.selectTrack(audioIndex);

                                if (startTime > 0) {
                                    audioExtractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                                } else {
                                    audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                                }

                                audioTranscoder = new AudioTranscoder(audioFormat, audioExtractor, audioIndex);
                                audioTranscoder.startTime = startTime;
                                audioTranscoder.endTime = endTime;
                            }
                        }
                        boolean audioEncoderDone = false;

                        checkConversionCanceled();

                        while (!outputDone || (!copyAudioBuffer && !audioEncoderDone)) {
                            checkConversionCanceled();

                            if (!copyAudioBuffer && audioTranscoder != null) {
                                audioEncoderDone = audioTranscoder.step(mediaMuxer, audioTrackIndex);
                            }

                            if (!inputDone) {
                                boolean eof = false;
                                int index = extractor.getSampleTrackIndex();
                                if (index == videoIndex) {
                                    int inputBufIndex = decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT);
                                    if (inputBufIndex >= 0) {
                                        ByteBuffer inputBuf;
                                        if (Build.VERSION.SDK_INT < 21) {
                                            inputBuf = decoderInputBuffers[inputBufIndex];
                                        } else {
                                            inputBuf = decoder.getInputBuffer(inputBufIndex);
                                        }
                                        int chunkSize = extractor.readSampleData(inputBuf, 0);
                                        if (chunkSize < 0) {
                                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                            inputDone = true;
                                        } else {
                                            decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, extractor.getSampleTime(), 0);
                                            extractor.advance();
                                        }
                                    }
                                } else if (copyAudioBuffer && audioIndex != -1 && index == audioIndex) {
                                    info.size = extractor.readSampleData(audioBuffer, 0);
                                    if (Build.VERSION.SDK_INT < 21) {
                                        audioBuffer.position(0);
                                        audioBuffer.limit(info.size);
                                    }
                                    if (info.size >= 0) {
                                        info.presentationTimeUs = extractor.getSampleTime();
                                        extractor.advance();
                                    } else {
                                        info.size = 0;
                                        inputDone = true;
                                    }
                                    if (info.size > 0 && (endTime < 0 || info.presentationTimeUs < endTime)) {
                                        info.offset = 0;
                                        info.flags = extractor.getSampleFlags();
                                        long availableSize = mediaMuxer.writeSampleData(audioTrackIndex, audioBuffer, info, false);
                                        if (availableSize != 0) {
                                            if(callback != null) callback.didWriteData(availableSize);
                                        }
                                    }
                                } else if (index == -1) {
                                    eof = true;
                                }
                                if (eof) {
                                    int inputBufIndex = decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT);
                                    if (inputBufIndex >= 0) {
                                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        inputDone = true;
                                    }
                                }
                            }

                            boolean decoderOutputAvailable = !decoderDone;
                            boolean encoderOutputAvailable = true;
                            while (decoderOutputAvailable || encoderOutputAvailable) {
                                checkConversionCanceled();
                                int encoderStatus = encoder.dequeueOutputBuffer(info, 2500);
                                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                    encoderOutputAvailable = false;
                                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                    if (Build.VERSION.SDK_INT < 21) {
                                        encoderOutputBuffers = encoder.getOutputBuffers();
                                    }
                                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    MediaFormat newFormat = encoder.getOutputFormat();
                                    if (videoTrackIndex == -5) {
                                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                                    }
                                } else if (encoderStatus < 0) {
                                    throw new RuntimeException("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                                } else {
                                    ByteBuffer encodedData;
                                    if (Build.VERSION.SDK_INT < 21) {
                                        encodedData = encoderOutputBuffers[encoderStatus];
                                    } else {
                                        encodedData = encoder.getOutputBuffer(encoderStatus);
                                    }
                                    if (encodedData == null) {
                                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                                    }
                                    if (info.size > 1) {
                                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            long availableSize = mediaMuxer.writeSampleData(videoTrackIndex, encodedData, info, true);
                                            if (availableSize != 0) {
                                                if(callback != null) callback.didWriteData(availableSize);
                                            }
                                        } else if (videoTrackIndex == -5) {
                                            byte[] csd = new byte[info.size];
                                            encodedData.limit(info.offset + info.size);
                                            encodedData.position(info.offset);
                                            encodedData.get(csd);
                                            ByteBuffer sps = null;
                                            ByteBuffer pps = null;
                                            for (int a = info.size - 1; a >= 0; a--) {
                                                if (a > 3) {
                                                    if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                                                        sps = ByteBuffer.allocate(a - 3);
                                                        pps = ByteBuffer.allocate(info.size - (a - 3));
                                                        sps.put(csd, 0, a - 3).position(0);
                                                        pps.put(csd, a - 3, info.size - (a - 3)).position(0);
                                                        break;
                                                    }
                                                } else {
                                                    break;
                                                }
                                            }

                                            MediaFormat newFormat = MediaFormat.createVideoFormat(MediaController.VIDEO_MIME_TYPE, resultWidth, resultHeight);
                                            if (sps != null && pps != null) {
                                                newFormat.setByteBuffer("csd-0", sps);
                                                newFormat.setByteBuffer("csd-1", pps);
                                            }
                                            videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                                        }
                                    }
                                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                                    encoder.releaseOutputBuffer(encoderStatus, false);
                                }
                                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                                    continue;
                                }

                                if (!decoderDone) {
                                    int decoderStatus = decoder.dequeueOutputBuffer(info, MEDIACODEC_TIMEOUT_DEFAULT);
                                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        decoderOutputAvailable = false;
                                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        MediaFormat newFormat = decoder.getOutputFormat();
                                        if (BuildVars.LOGS_ENABLED) {
                                            FileLog.d("newFormat = " + newFormat);
                                        }
                                    } else if (decoderStatus < 0) {
                                        throw new RuntimeException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                                    } else {
                                        boolean doRender;
                                        if (Build.VERSION.SDK_INT >= 18) {
                                            doRender = info.size != 0;
                                        } else {
                                            doRender = info.size != 0 || info.presentationTimeUs != 0;
                                        }
                                        if (endTime > 0 && info.presentationTimeUs >= endTime) {
                                            inputDone = true;
                                            decoderDone = true;
                                            doRender = false;
                                            info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                        }
                                        if (startTime > 0 && videoTime == -1) {
                                            if (info.presentationTimeUs < startTime) {
                                                doRender = false;
                                                if (BuildVars.LOGS_ENABLED) {
                                                    FileLog.d("drop frame startTime = " + startTime + " present time = " + info.presentationTimeUs);
                                                }
                                            } else {
                                                videoTime = info.presentationTimeUs;
                                            }
                                        }
                                        decoder.releaseOutputBuffer(decoderStatus, doRender);
                                        if (doRender) {
                                            boolean errorWait = false;
                                            try {
                                                outputSurface.awaitNewImage();
                                            } catch (Exception e) {
                                                errorWait = true;
                                                FileLog.e(e);
                                            }
                                            if (!errorWait) {
                                                if (Build.VERSION.SDK_INT >= 18) {
                                                    outputSurface.drawImage(false);
                                                    inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                                                    inputSurface.swapBuffers();
                                                } else {
                                                    int inputBufIndex = encoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT);
                                                    if (inputBufIndex >= 0) {
                                                        outputSurface.drawImage(true);
                                                        ByteBuffer rgbBuf = outputSurface.getFrame();
                                                        ByteBuffer yuvBuf = encoderInputBuffers[inputBufIndex];
                                                        yuvBuf.clear();
                                                        Utilities.convertVideoFrame(rgbBuf, yuvBuf, colorFormat, resultWidth, resultHeight, padding, swapUV);
                                                        encoder.queueInputBuffer(inputBufIndex, 0, bufferSize, info.presentationTimeUs, 0);
                                                    } else {
                                                        if (BuildVars.LOGS_ENABLED) {
                                                            FileLog.d("input buffer not available");
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                            decoderOutputAvailable = false;
                                            if (BuildVars.LOGS_ENABLED) {
                                                FileLog.d("decoder stream end");
                                            }
                                            if (Build.VERSION.SDK_INT >= 18) {
                                                encoder.signalEndOfInputStream();
                                            } else {
                                                int inputBufIndex = encoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT);
                                                if (inputBufIndex >= 0) {
                                                    encoder.queueInputBuffer(inputBufIndex, 0, 1, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // in some case encoder.dequeueOutputBuffer return IllegalStateException
                        // stable reproduced on xiaomi
                        // fix it by increasing timeout
//                        if (e instanceof IllegalStateException && timoutUsec != MEDIACODEC_TIMEOUT_INCREASED) {
//                            repeatWithIncreasedTimeout = true;
//                        }
                        e.printStackTrace();
                        FileLog.e("bitrate: " + bitrate + " framerate: " + framerate + " size: " + resultHeight + "x" + resultWidth);
                        FileLog.e(e);
                        e.printStackTrace();
                        error = true;
                    }

                    extractor.unselectTrack(videoIndex);

                    if (outputSurface != null) {
                        outputSurface.release();
                    }
                    if (inputSurface != null) {
                        inputSurface.release();
                    }
                    if (decoder != null) {
                        decoder.stop();
                        decoder.release();
                    }
                    if (encoder != null) {
                        encoder.stop();
                        encoder.release();
                    }

                    if (audioTranscoder != null) audioTranscoder.release();
                    checkConversionCanceled();
                }
            } else {
                readAndWriteTracks(extractor, mediaMuxer, info, startTime, endTime, cacheFile, bitrate != -1);
            }
        } catch (Exception e) {
            error = true;
            e.printStackTrace();
            FileLog.e("bitrate: " + bitrate + " framerate: " + framerate + " size: " + resultHeight + "x" + resultWidth);
            FileLog.e(e);
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (mediaMuxer != null) {
                try {
                    mediaMuxer.finishMovie();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (BuildVars.LOGS_ENABLED) {
               // FileLog.d("time = " + (System.currentTimeMillis() - time));
            }
        }

        return error;
    }

    private long readAndWriteTracks(MediaExtractor extractor, MP4Builder mediaMuxer,
                                    MediaCodec.BufferInfo info, long start, long end, File file, boolean needAudio) throws Exception {
        int videoTrackIndex = MediaController.findTrack(extractor, false);
        int audioTrackIndex = needAudio ? MediaController.findTrack(extractor, true) : -1;
        int muxerVideoTrackIndex = -1;
        int muxerAudioTrackIndex = -1;
        boolean inputDone = false;
        int maxBufferSize = 0;
        if (videoTrackIndex >= 0) {
            extractor.selectTrack(videoTrackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(videoTrackIndex);
            muxerVideoTrackIndex = mediaMuxer.addTrack(trackFormat, false);
            maxBufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
        }
        if (audioTrackIndex >= 0) {
            extractor.selectTrack(audioTrackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(audioTrackIndex);
            muxerAudioTrackIndex = mediaMuxer.addTrack(trackFormat, true);
            maxBufferSize = Math.max(trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), maxBufferSize);
            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        if (audioTrackIndex >= 0 || videoTrackIndex >= 0) {
            long startTime = -1;
            checkConversionCanceled();
            while (!inputDone) {
                checkConversionCanceled();
                boolean eof = false;
                int muxerTrackIndex;
                info.size = extractor.readSampleData(buffer, 0);
                int index = extractor.getSampleTrackIndex();
                if (index == videoTrackIndex) {
                    muxerTrackIndex = muxerVideoTrackIndex;
                } else if (index == audioTrackIndex) {
                    muxerTrackIndex = muxerAudioTrackIndex;
                } else {
                    muxerTrackIndex = -1;
                }
                if (muxerTrackIndex != -1) {
                    if (Build.VERSION.SDK_INT < 21) {
                        buffer.position(0);
                        buffer.limit(info.size);
                    }
                    if (index != audioTrackIndex) {
                        byte[] array = buffer.array();
                        if (array != null) {
                            int offset = buffer.arrayOffset();
                            int len = offset + buffer.limit();
                            int writeStart = -1;
                            for (int a = offset; a <= len - 4; a++) {
                                if (array[a] == 0 && array[a + 1] == 0 && array[a + 2] == 0 && array[a + 3] == 1 || a == len - 4) {
                                    if (writeStart != -1) {
                                        int l = a - writeStart - (a != len - 4 ? 4 : 0);
                                        array[writeStart] = (byte) (l >> 24);
                                        array[writeStart + 1] = (byte) (l >> 16);
                                        array[writeStart + 2] = (byte) (l >> 8);
                                        array[writeStart + 3] = (byte) l;
                                        writeStart = a;
                                    } else {
                                        writeStart = a;
                                    }
                                }
                            }
                        }
                    }
                    if (info.size >= 0) {
                        info.presentationTimeUs = extractor.getSampleTime();
                    } else {
                        info.size = 0;
                        eof = true;
                    }

                    if (info.size > 0 && !eof) {
                        if (index == videoTrackIndex && start > 0 && startTime == -1) {
                            startTime = info.presentationTimeUs;
                        }
                        if (end < 0 || info.presentationTimeUs < end) {
                            info.offset = 0;
                            info.flags = extractor.getSampleFlags();
                            long availableSize = mediaMuxer.writeSampleData(muxerTrackIndex, buffer, info, false);
                            if (availableSize != 0) {
                                if(callback != null) callback.didWriteData(availableSize);
                            }
                        } else {
                            eof = true;
                        }
                    }
                    if (!eof) {
                        extractor.advance();
                    }
                } else if (index == -1) {
                    eof = true;
                } else {
                    extractor.advance();
                }
                if (eof) {
                    inputDone = true;
                }
            }
            if (videoTrackIndex >= 0) {
                extractor.unselectTrack(videoTrackIndex);
            }
            if (audioTrackIndex >= 0) {
                extractor.unselectTrack(audioTrackIndex);
            }
            return startTime;
        }
        return -1;
    }

    private void checkConversionCanceled() {
        if(callback != null && callback.checkConversionCanceled())  throw new RuntimeException("canceled conversion");
    }
}
