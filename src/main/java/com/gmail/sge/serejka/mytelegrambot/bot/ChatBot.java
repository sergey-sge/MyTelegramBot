package com.gmail.sge.serejka.mytelegrambot.bot;

import com.gmail.sge.serejka.mytelegrambot.model.User;
import com.gmail.sge.serejka.mytelegrambot.service.UserService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.util.List;

@Component
@PropertySource("classpath:telegram.properties")
public class ChatBot extends TelegramLongPollingBot {
    private static final Logger LOGGER = LogManager.getLogger(ChatBot.class);

    private static final String BROADCAST = "broadcast ";
    private static final String LIST_USERS = "users";
    private static final String IS_ADMIN = "is_admin";
    private static final String DELETE_MY_ACCOUNT = "delete_my_account";

    @Value("${bot.name}")
    private String botName;

    @Value("${bot.token}")
    private String botToken;

    private final UserService userService;

    public ChatBot(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText())
            return;

        final String text = update.getMessage().getText();
        final long chatId = update.getMessage().getChatId();

        User user = userService.findByChatId(chatId);

        if (checkIfAdminCommand(user, text))
            return;

        BotContext context;
        BotState state;

        if (user == null) {
            state = BotState.getInitialState();

            user = new User(chatId, state.ordinal());
            userService.addUser(user);

            context = BotContext.of(this, user, text);
            state.enter(context);

            LOGGER.info("New user registered: " + chatId);
        } else {
            context = BotContext.of(this, user, text);
            state = BotState.byId(user.getStateId());

            LOGGER.info("Update received for user in state: " + state);
        }

        state.handleInput(context);

        do {
            state = state.nextState();
            state.enter(context);
        } while (!state.isInputNeeded());

        user.setStateId(state.ordinal());
        userService.updateUser(user);
    }

    private boolean checkIfAdminCommand(User user, String text) {
        if (text.equals(DELETE_MY_ACCOUNT)){
            long chatId = user.getChatId();
            userService.deleteUser(user);
            sendMessage(chatId,"Your accound was deleted");
            return true;
        }

        if (user == null || !user.getAdmin())
            return false;

        if (text.startsWith(BROADCAST)) {
            LOGGER.info("Admin command received: " + BROADCAST);

            text = text.substring(BROADCAST.length());
            broadcast(text);

            return true;
        } else if (text.equals(LIST_USERS)) {
            LOGGER.info("Admin command received: " + LIST_USERS);

            listUsers(user);
            return true;
        } else if (text.startsWith(IS_ADMIN)){
            LOGGER.info("Admin command received: " + IS_ADMIN);
            String phoneNumber = text.substring(IS_ADMIN.length() + 1);
            setAdmin(phoneNumber);
            sendMessage(user.getChatId(),"User " + phoneNumber + " is admin now");
            return true;
        }

        return false;
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendPhoto(long chatId) {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("test.png");

        SendPhoto message = new SendPhoto();
        message.setChatId(Long.toString(chatId));
        message.setPhoto(new InputFile(is, "test"));
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }



    private void listUsers(User admin) {
        StringBuilder sb = new StringBuilder("All users list:\r\n");
        List<User> users = userService.findAllUsers();

        users.forEach(user ->
                sb.append(user.getId())
                        .append(' ')
                        .append(user.getPhone())
                        .append(' ')
                        .append(user.getEmail())
                        .append("\r\n")
        );

        sendPhoto(admin.getChatId());
        sendMessage(admin.getChatId(), sb.toString());
    }

    private void setAdmin(String phone){
        User user = userService.findByPhone(phone);
        user.setAdmin(true);
        userService.updateUser(user);
        sendMessage(user.getChatId(), "You are admin now");
    }

    private void broadcast(String text) {
        List<User> users = userService.findAllUsers();
        users.forEach(user -> sendMessage(user.getChatId(), text));
    }
}