// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package codeu.controller;

import codeu.model.data.Conversation;
import codeu.model.data.Mention;
import codeu.model.data.Message;
import codeu.model.data.User;
import codeu.model.store.basic.ConversationStore;
import codeu.model.store.basic.MessageStore;
import codeu.model.store.basic.UserStore;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.repackaged.com.google.common.collect.ImmutableList;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;


/** Servlet class responsible for the chat page. */
public class ChatServlet extends HttpServlet {

  /** Store class that gives access to Conversations. */
  private ConversationStore conversationStore;

  /** Store class that gives access to Messages. */
  private MessageStore messageStore;

  /** Store class that gives access to Users. */
  private UserStore userStore;

  /** Set up state for handling chat requests. */
  @Override
  public void init() throws ServletException {
    super.init();
    setConversationStore(ConversationStore.getInstance());
    setMessageStore(MessageStore.getInstance());
    setUserStore(UserStore.getInstance());
  }

  /**
   * Sets the ConversationStore used by this servlet. This function provides a common setup method
   * for use by the test framework or the servlet's init() function.
   */
  void setConversationStore(ConversationStore conversationStore) {
    this.conversationStore = conversationStore;
  }

  /**
   * Sets the MessageStore used by this servlet. This function provides a common setup method for
   * use by the test framework or the servlet's init() function.
   */
  void setMessageStore(MessageStore messageStore) {
    this.messageStore = messageStore;
  }

  /**
   * Sets the UserStore used by this servlet. This function provides a common setup method for use
   * by the test framework or the servlet's init() function.
   */
  void setUserStore(UserStore userStore) {
    this.userStore = userStore;
  }

  /**
   * This function fires when a user navigates to the chat page. It gets the conversation title from
   * the URL, finds the corresponding Conversation, and fetches the messages in that Conversation.
   * It then forwards to chat.jsp for rendering.
   */
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    String requestUrl = request.getRequestURI();
    String conversationTitle = requestUrl.substring("/chat/".length());

    Conversation conversation = conversationStore.getConversationWithTitle(conversationTitle);
    if (conversation == null) {
      // couldn't find conversation, redirect to conversation list
      System.out.println("Conversation was null: " + conversationTitle);
      response.sendRedirect("/conversations");
      return;
    }

    UUID conversationId = conversation.getId();

    List<Message> messages = messageStore.getMessagesInConversation(conversationId);

    request.setAttribute("conversation", conversation);
    request.setAttribute("messages", messages);
    request.getRequestDispatcher("/WEB-INF/view/chat.jsp").forward(request, response);
  }

  /**
   * This function fires when a user submits the form on the chat page. It gets the logged-in
   * username from the session, the conversation title from the URL, and the chat message from the
   * submitted form data. It creates a new Message from that data, adds it to the model, and then
   * redirects back to the chat page.
   */
  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {

    String username = (String) request.getSession().getAttribute("user");
    if (username == null) {
      // user is not logged in, don't let them add a message
      response.sendRedirect("/login");
      return;
    }

    User user = userStore.getUser(username);
    if (user == null || user.isBlocked()) {
      // user was not found, don't let them add a message
      response.sendRedirect("/login");
      return;
    }

    String requestUrl = request.getRequestURI();
    String conversationTitle = requestUrl.substring("/chat/".length());

    Conversation conversation = conversationStore.getConversationWithTitle(conversationTitle);
    if (conversation == null || conversation.isMuted()) {
      // couldn't find conversation, or the conversation can't be shown: redirect to conversation
      // list
      response.sendRedirect("/conversations");
      return;
    }

    String messageContent = request.getParameter("message");

    // this removes any HTML from the message content
    String cleanedMessageContent = Jsoup.clean(messageContent, Whitelist.none());

    Message message =
        new Message(
            UUID.randomUUID(),
            conversation.getId(),
            user.getId(),
            cleanedMessageContent,
            Instant.now());

    messageStore.addMessage(message);

    // redirect to a GET request
    response.sendRedirect("/chat/" + conversationTitle);

    //Get mentions in an individual single message
    List<Mention> mentionsList = getMentions(message);

  }
  public List<Mention> getMentions(Message message){
    List<Mention> mentions = new ArrayList<>();
    String content = message.getContent();

    Mention mention = null;
    boolean inMention = false;
    int startOfMention = -1;
    int endOfMention = -1;

    for (int i = 0; i < content.length(); i++) {

      if(content.charAt(i)=='@'){
        if(inMention){
          inMention = false;
          break;
        }
        else if(i==0 || content.charAt(i-1)==' '){
          inMention = true;
          startOfMention = i;
        }

        else if(content.charAt(i) == ' '){
          if(inMention){
            endOfMention = i;

            mention = getMention(startOfMention, endOfMention, message);
            if(!(mention == null)){
              mentions.add(mention);
            }
            inMention = false;
          }


        }
      }
    }

    return mentions;
  }

  public Mention getMention(int s, int e, Message m){
    String content = m.getContent();
    int start = s;
    int end = e;
    StringBuilder sb = new StringBuilder();

    Mention mention = null;

    for (int i = s; i < end; i++) {
        sb.append(content.charAt(i));
    }

    String userMentioned = sb.toString();
    UUID userMentionedID = searchForUser(userMentioned);

    if(!(userMentionedID == null)){
      mention = new Mention(
              userMentionedID,
              m.getAuthorId(),
              start,
              end,
              m.getCreationTime(),
              m);
    }
    return mention;
  }

  //Could be moved to UserStore
  public UUID searchForUser(String username){
    UUID userID = null;


    for (User user : userStore.getAllUsers()) {
      if(username.equalsIgnoreCase(user.getName())){
        userID = user.getId();
      }
    }

    return userID;
  }
}
