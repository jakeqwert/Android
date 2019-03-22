/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.feedback.ui.common

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.duckduckgo.app.browser.BuildConfig
import com.duckduckgo.app.feedback.api.FeedbackSubmitter
import com.duckduckgo.app.feedback.ui.common.FragmentState.*
import com.duckduckgo.app.feedback.ui.negative.FeedbackType
import com.duckduckgo.app.feedback.ui.negative.FeedbackType.MainReason
import com.duckduckgo.app.feedback.ui.negative.FeedbackType.SubReason
import com.duckduckgo.app.global.SingleLiveEvent
import com.duckduckgo.app.global.coroutine.DispatcherProvider
import com.duckduckgo.app.playstore.PlayStoreUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber


class FeedbackViewModel(
    private val playStoreUtils: PlayStoreUtils,
    private val feedbackSubmitter: FeedbackSubmitter,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider.PRODUCTION
) : ViewModel() {

    val viewState: MutableLiveData<ViewState> = MutableLiveData()

    init {
        viewState.postValue(ViewState(fragmentViewState = InitialAppEnjoymentClarifier(NAVIGATION_FORWARDS)))
    }

    private val currentViewState: ViewState
        get() = viewState.value!!

    val command: SingleLiveEvent<Command> = SingleLiveEvent()


    fun userSelectedNegativeFeedbackMainReason(mainReason: MainReason) {
        val newState = when (mainReason) {
            MainReason.MISSING_BROWSING_FEATURES -> NegativeFeedbackSubReason(NAVIGATION_FORWARDS, mainReason)
            MainReason.WEBSITES_NOT_LOADING -> NegativeWebSitesBrokenFeedback(NAVIGATION_FORWARDS, mainReason)
            MainReason.SEARCH_NOT_GOOD_ENOUGH -> NegativeFeedbackSubReason(NAVIGATION_FORWARDS, mainReason)
            MainReason.NOT_ENOUGH_CUSTOMIZATIONS -> NegativeFeedbackSubReason(NAVIGATION_FORWARDS, mainReason)
            MainReason.APP_IS_SLOW_OR_BUGGY -> NegativeFeedbackSubReason(NAVIGATION_FORWARDS, mainReason)
            MainReason.OTHER -> NegativeOpenEndedFeedback(NAVIGATION_FORWARDS, mainReason)
        }
        viewState.value = currentViewState.copy(
                fragmentViewState = newState,
                mainReason = mainReason,
                subReason = null,
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun onBackPressed() {
        when (currentViewState.fragmentViewState) {
            is InitialAppEnjoymentClarifier -> {
                command.value = Command.Exit(feedbackSubmitted = false)
            }
            is PositiveFeedbackFirstStep -> {
                viewState.value = currentViewState.copy(fragmentViewState = InitialAppEnjoymentClarifier(NAVIGATION_BACKWARDS))
            }
            is PositiveShareFeedback -> {
                if (canShowRatingsButton()) {
                    viewState.value = currentViewState.copy(fragmentViewState = PositiveFeedbackFirstStep(NAVIGATION_BACKWARDS))
                } else {
                    viewState.value = currentViewState.copy(fragmentViewState = InitialAppEnjoymentClarifier(NAVIGATION_BACKWARDS))
                }
            }
            is NegativeFeedbackMainReason -> {
                viewState.value = currentViewState.copy(fragmentViewState = InitialAppEnjoymentClarifier(NAVIGATION_BACKWARDS))
            }
            is NegativeFeedbackSubReason -> {
                viewState.value = currentViewState.copy(fragmentViewState = NegativeFeedbackMainReason(NAVIGATION_BACKWARDS))
            }
            is NegativeWebSitesBrokenFeedback -> {
                viewState.value = currentViewState.copy(fragmentViewState = NegativeFeedbackMainReason(NAVIGATION_BACKWARDS))
            }
            is NegativeOpenEndedFeedback -> {
                val newViewState = when (currentViewState.previousViewState) {
                    is NegativeFeedbackSubReason -> {
                        NegativeFeedbackSubReason(NAVIGATION_BACKWARDS, currentViewState.mainReason!!)
                    }
                    is NegativeFeedbackMainReason -> {
                        NegativeFeedbackMainReason(NAVIGATION_BACKWARDS)
                    }
                    else -> {
                        NegativeFeedbackMainReason(NAVIGATION_BACKWARDS)
                    }
                }
                viewState.value = currentViewState.copy(fragmentViewState = newViewState)
            }
        }
    }

    fun userSelectedPositiveFeedback() {
        viewState.value = if (canShowRatingsButton()) {
            currentViewState.copy(
                fragmentViewState = PositiveFeedbackFirstStep(NAVIGATION_FORWARDS),
                    previousViewState = currentViewState.fragmentViewState
            )
        } else {
            currentViewState.copy(
                    fragmentViewState = PositiveShareFeedback(NAVIGATION_FORWARDS),
                    previousViewState = currentViewState.fragmentViewState
            )
        }
    }

    private fun canShowRatingsButton(): Boolean {
        val playStoreInstalled = playStoreUtils.isPlayStoreInstalled()

        if (!playStoreInstalled) {
            Timber.i("Play Store not installed")
            return false
        }

        if (playStoreUtils.installedFromPlayStore()) {
            return true
        }

        if (BuildConfig.DEBUG) {
            Timber.i("Not installed from the Play Store but it is DEBUG; will treat as if installed from Play Store")
            return true
        }
        return false
    }

    fun userSelectedNegativeFeedback() {
        viewState.value = currentViewState.copy(
                fragmentViewState = NegativeFeedbackMainReason(NAVIGATION_FORWARDS),
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userWantsToCancel() {
        Timber.i("User is cancelling")
        command.value = Command.Exit(feedbackSubmitted = false)
    }

    fun userSelectedToGiveFeedback() {
        viewState.value = currentViewState.copy(
                fragmentViewState = PositiveShareFeedback(NAVIGATION_FORWARDS),
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userProvidedNegativeOpenEndedFeedback(mainReason: MainReason, subReason: SubReason?, feedback: String) {
        GlobalScope.launch(dispatcherProvider.IO) {
            feedbackSubmitter.sendNegativeFeedback(mainReason, subReason, feedback)
        }

        command.value = Command.Exit(feedbackSubmitted = true)
    }

    fun onProvidedBrokenSiteFeedback(feedback: String, brokenSite: String?) {
        GlobalScope.launch(dispatcherProvider.IO) {
            feedbackSubmitter.sendBrokenSiteFeedback(feedback, brokenSite)
        }

        command.value = Command.Exit(feedbackSubmitted = true)
    }

    fun userGavePositiveFeedbackNoDetails() {
        GlobalScope.launch(dispatcherProvider.IO) {
            feedbackSubmitter.sendPositiveFeedback(null)
        }

        command.value = Command.Exit(feedbackSubmitted = true)
    }

    fun userProvidedPositiveOpenEndedFeedback(feedback: String) {
        GlobalScope.launch(dispatcherProvider.IO) {
            feedbackSubmitter.sendPositiveFeedback(feedback)
        }

        command.value = Command.Exit(feedbackSubmitted = true)
    }

    fun userSelectedSubReasonMissingBrowserFeatures(mainReason: MainReason, subReason: FeedbackType.MissingBrowserFeaturesSubReasons) {
        val newState = NegativeOpenEndedFeedback(NAVIGATION_FORWARDS, mainReason, subReason)
        viewState.value = currentViewState.copy(
                fragmentViewState = newState,
                mainReason = mainReason,
                subReason = subReason,
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userSelectedSubReasonSearchNotGoodEnough(mainReason: MainReason, subReason: FeedbackType.SearchNotGoodEnoughSubReasons) {
        val newState = NegativeOpenEndedFeedback(NAVIGATION_FORWARDS, mainReason, subReason)
        viewState.value = currentViewState.copy(
                fragmentViewState = newState,
                mainReason = mainReason,
                subReason = subReason,
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userSelectedSubReasonNeedMoreCustomization(mainReason: MainReason, subReason: FeedbackType.CustomizationSubReasons) {
        val newState = NegativeOpenEndedFeedback(NAVIGATION_FORWARDS, mainReason, subReason)
        viewState.value = currentViewState.copy(
                fragmentViewState = newState,
                mainReason = mainReason,
                subReason = subReason,
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userSelectedSubReasonAppIsSlowOrBuggy(mainReason: MainReason, subReason: FeedbackType.PerformanceSubReasons) {
        val newState = NegativeOpenEndedFeedback(NAVIGATION_FORWARDS, mainReason, subReason)
        viewState.value = currentViewState.copy(
                fragmentViewState = newState,
                mainReason = mainReason,
                subReason = subReason,
                previousViewState = currentViewState.fragmentViewState
        )
    }

    fun userSelectedToRateApp() {
        GlobalScope.launch(dispatcherProvider.IO) {
            feedbackSubmitter.sendUserRated()
        }

        command.value = Command.Exit(feedbackSubmitted = true)
    }

    companion object {
        const val NAVIGATION_FORWARDS = true
        const val NAVIGATION_BACKWARDS = false
    }
}

data class ViewState(
        val fragmentViewState: FragmentState,
        val previousViewState: FragmentState? = null,
        val mainReason: MainReason? = null,
        val subReason: SubReason? = null
)

sealed class FragmentState(open val forwardDirection: Boolean) {
    data class InitialAppEnjoymentClarifier(override val forwardDirection: Boolean) : FragmentState(forwardDirection)

    // positive flow
    data class PositiveFeedbackFirstStep(override val forwardDirection: Boolean) : FragmentState(forwardDirection)
    data class PositiveShareFeedback(override val forwardDirection: Boolean) : FragmentState(forwardDirection)

    // negative flow
    data class NegativeFeedbackMainReason(override val forwardDirection: Boolean) : FragmentState(forwardDirection)
    data class NegativeFeedbackSubReason(override val forwardDirection: Boolean, val mainReason: MainReason) : FragmentState(forwardDirection)
    data class NegativeOpenEndedFeedback(override val forwardDirection: Boolean, val mainReason: MainReason, val subReason: SubReason? = null) :
            FragmentState(forwardDirection)

    data class NegativeWebSitesBrokenFeedback(
            override val forwardDirection: Boolean,
            val mainReason: MainReason,
            val subReason: SubReason? = null
    ) : FragmentState(forwardDirection)
}

sealed class Command {
    data class Exit(val feedbackSubmitted: Boolean) : Command()
}