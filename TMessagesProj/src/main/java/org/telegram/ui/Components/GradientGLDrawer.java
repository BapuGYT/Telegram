package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;

import androidx.annotation.ColorInt;
import androidx.annotation.FloatRange;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.Paint.FragmentShader;

public class GradientGLDrawer implements GLTextureView.Drawer {

    private static final float[] POINT_COORDS = new float[] {
            0.35f, 0.25f,  0.82f, 0.08f,  0.65f, 0.75f,  0.18f, 0.92f
    };
    private static final int[] DEFAULT_COLORS = new int[] {
            0xFFF6BF, 0x76A076, 0xF6E477, 0x316B4D
    };

    private static final int COLOR_SIZE = 3;
    private static final int POINT_SIZE = 2;

    private final String fragmentShaderSource;
    private FragmentShader shader;

    private final float[] colors = new float[COLOR_SIZE * 4];
    private final float[] points = new float[POINT_SIZE * 4];
    private float width;
    private float height;

    private int locResolution = -1;
    private int locColor1 = -1;
    private int locColor2 = -1;
    private int locColor3 = -1;
    private int locColor4 = -1;
    private int locPoint1 = -1;
    private int locPoint2 = -1;
    private int locPoint3 = -1;
    private int locPoint4 = -1;

    public GradientGLDrawer(Context context) {
        fragmentShaderSource = AndroidUtilities.readTextFromAsset(context, "shaders/gradient_background.frag", true);
        for (int i = 0; i != 4; ++i) {
            setColorPoint(i, DEFAULT_COLORS[i], POINT_COORDS[i * 2], POINT_COORDS[i * 2 + 1]);
        }
    }

    @Override
    public void init(int width, int height) {
        this.width = width;
        this.height = height;

        shader = new FragmentShader(fragmentShaderSource);
        int program = shader.getProgram();
        locResolution = GLES20.glGetUniformLocation(program, "u_resolution");

        locColor1 = GLES20.glGetUniformLocation(program, "u_color1");
        locColor2 = GLES20.glGetUniformLocation(program, "u_color2");
        locColor3 = GLES20.glGetUniformLocation(program, "u_color3");
        locColor4 = GLES20.glGetUniformLocation(program, "u_color4");

        locPoint1 = GLES20.glGetUniformLocation(program, "u_point1");
        locPoint2 = GLES20.glGetUniformLocation(program, "u_point2");
        locPoint3 = GLES20.glGetUniformLocation(program, "u_point3");
        locPoint4 = GLES20.glGetUniformLocation(program, "u_point4");
    }

    @Override
    public void draw() {
        if (shader == null) {
            return;
        }

        GLES20.glUseProgram(shader.getProgram());
        GLES20.glUniform2f(locResolution, width, height);

        GLES20.glUniform3fv(locColor1, 1, colors, 0);
        GLES20.glUniform3fv(locColor2, 1, colors, COLOR_SIZE);
        GLES20.glUniform3fv(locColor3, 1, colors, COLOR_SIZE * 2);
        GLES20.glUniform3fv(locColor4, 1, colors, COLOR_SIZE * 3);

        GLES20.glUniform2fv(locPoint1, 1, points, 0);
        GLES20.glUniform2fv(locPoint2, 1, points, POINT_SIZE);
        GLES20.glUniform2fv(locPoint3, 1, points, POINT_SIZE * 2);
        GLES20.glUniform2fv(locPoint4, 1, points, POINT_SIZE * 3);

        shader.draw();
    }

    @Override
    public void release() {
        if (shader != null) {
            shader.cleanResources();
            shader = null;
        }
    }

    void setColor(int idx, @ColorInt int color) {
        colors[idx * 3] = Color.red(color) / 255f;
        colors[idx * 3 + 1] = Color.green(color) / 255f;
        colors[idx * 3 + 2] = Color.blue(color) / 255f;
    }

    /**
     * x from left to right
     * y from top to bottom
     */
    void setPosition(int idx, float x, float y) {
        points[idx * 2] = x;
        points[idx * 2 + 1] = 1f - y;
    }

    void setColorPoint(int idx, @ColorInt int color, float x, float y) {
        setColor(idx, color);
        setPosition(idx, x, y);
    }
}
