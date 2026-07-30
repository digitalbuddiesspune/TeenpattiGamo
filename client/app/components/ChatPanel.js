export default function ChatPanel({ messages }) {
  return (
    <aside className="chat-panel">
      <div className="chat-title">TABLE CHAT</div>
      <div className="chat-feed">
        {messages?.map((message, index) => (
          <div className="chat-line" key={`${message}-${index}`}>
            {message}
          </div>
        ))}
      </div>
      <div className="chat-input-row">
        <button className="chat-dismiss" type="button">X</button>
        <div className="chat-input">Hello friend, I&apos;m Teen Patti Master.</div>
        <button className="chat-send" type="button">{">"}</button>
      </div>
    </aside>
  );
}
