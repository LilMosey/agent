import { client, unwrap } from './client';
import type { Conversation, ConversationMessage, SendMessageResponse } from '../types/domain';

export async function createConversation(): Promise<Conversation> {
  const response = await client.post('/conversations');
  return unwrap<Conversation>(response);
}

export async function listConversations(): Promise<Conversation[]> {
  const response = await client.get('/conversations');
  return unwrap<Conversation[]>(response);
}

export async function updateConversationTitle(conversationId: number, title: string): Promise<Conversation> {
  const response = await client.put(`/conversations/${conversationId}`, { title });
  return unwrap<Conversation>(response);
}

export async function deleteConversation(conversationId: number): Promise<void> {
  const response = await client.delete(`/conversations/${conversationId}`);
  unwrap<void>(response);
}

export async function listConversationMessages(conversationId: number): Promise<ConversationMessage[]> {
  const response = await client.get(`/conversations/${conversationId}/messages`);
  return unwrap<ConversationMessage[]>(response);
}

export async function sendConversationMessage(conversationId: number, content: string): Promise<SendMessageResponse> {
  const response = await client.post(`/conversations/${conversationId}/messages`, { content });
  return unwrap<SendMessageResponse>(response);
}
