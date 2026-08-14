package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.modules.messages.vm.MessageDetailsViewModel
import me.capcom.smsgateway.modules.messages.vm.MessagesListViewModel
import me.capcom.smsgateway.modules.messages.vm.SimStatsViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val messagesModule = module {
    single { MessagesRepository(get()) }
    singleOf(::MessagesService)
    viewModel { MessagesListViewModel(get()) }
    viewModel { MessageDetailsViewModel(get()) }
    viewModel { SimStatsViewModel(get(), get()) }
}

val MODULE_NAME = "messages"
