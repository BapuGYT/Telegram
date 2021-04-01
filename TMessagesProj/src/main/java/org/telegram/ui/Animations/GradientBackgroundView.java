package org.telegram.ui.Animations;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.Components.GLTextureView;

public class GradientBackgroundView extends GLTextureView {

    private final GradientGLDrawer drawer = new GradientGLDrawer(getContext());
    private final float[] animationStartPoints = new float[AnimationsController.backPointsCount * 2];
    private final float[] currentPoints = new float[AnimationsController.backPointsCount * 2];

    @Nullable
    private ValueAnimator animator;
    @Nullable
    private AnimationSettings settings;
    private int currentPointsPosition;

    public GradientBackgroundView(@NonNull Context context) {
        this(context, null);
    }

    public GradientBackgroundView(@NonNull Context context, @Nullable String name) {
        super(context, name);
        setDrawer(drawer);
        setPointsState(currentPointsPosition);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        super.onSurfaceTextureAvailable(surface, width, height);
        setColors(AnimationsController.getInstance().getBackgroundColorsCopy());
    }

    public void setColors(int[] colors) {
        for (int i = 0; i != colors.length; ++i) {
            drawer.setColor(i, colors[i]);
        }
        invalidate();
    }

    public void setSettings(@Nullable AnimationSettings settings) {
        this.settings = settings;
    }

    public void startAnimation() {
        int nextPosition = (currentPointsPosition + 1) % AnimationsController.backPositionsCount;
        startAnimation(nextPosition);
        currentPointsPosition = nextPosition;
    }

    private void startAnimation(int nextPointsPosition) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            for (int i = 0; i < AnimationsController.backPointsCount; ++i) {
                float xPrev = animationStartPoints[i * 2];
                float yPrev = animationStartPoints[i * 2 + 1];
                float xNext = AnimationsController.getBackgroundPointX(nextPointsPosition, i);
                float yNext = AnimationsController.getBackgroundPointY(nextPointsPosition, i);
                float xCurr = xPrev + (xNext - xPrev) * progress;
                float yCurr = yPrev + (yNext - yPrev) * progress;
                setPointPosition(i, xCurr, yCurr);
            }
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean isCancelled = false;
            @Override
            public void onAnimationStart(Animator animation) {
                isCancelled = false;
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                isCancelled = true;
                System.arraycopy(currentPoints, 0, animationStartPoints, 0, currentPoints.length);
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isCancelled) {
                    return;
                }
                for (int i = 0; i < AnimationsController.backPointsCount; ++i) {
                    float xNext = AnimationsController.getBackgroundPointX(nextPointsPosition, i);
                    float yNext = AnimationsController.getBackgroundPointY(nextPointsPosition, i);
                    setPointPosition(i, xNext, yNext);
                    setAnimationStartPoint(i, xNext, yNext);
                }
                invalidate();
            }
        });
        animator.setDuration(settings == null ? 500 : settings.maxDuration);
        if (settings != null) {
            Interpolator interpolator = settings.getInterpolator();
            if (interpolator != null) {
                animator.setInterpolator(interpolator);
            }
        }
        animator.start();
    }

    private void setPointsState(int position) {
        for (int i = 0; i < AnimationsController.backPointsCount; ++i) {
            float x = AnimationsController.getBackgroundPointX(position, i);
            float y = AnimationsController.getBackgroundPointY(position, i);
            setPointPosition(i, x, y);
            setAnimationStartPoint(i, x, y);
        }
        invalidate();
    }

    private void setPointPosition(int pointIdx, float x, float y) {
        drawer.setPosition(pointIdx, x, y);
        currentPoints[pointIdx * 2] = x;
        currentPoints[pointIdx * 2 + 1] = y;
    }

    private void setAnimationStartPoint(int pointIdx, float x, float y) {
        animationStartPoints[pointIdx * 2] = x;
        animationStartPoints[pointIdx * 2 + 1] = y;
    }
}
