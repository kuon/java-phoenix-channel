defmodule MockServerWeb.MockChannel do
  use MockServerWeb, :channel

  def join("mock:lobby", payload, socket) do
    if authorized?(payload) do
      {:ok, %{mock: "mockdata"}, socket}
    else
      {:error, %{reason: "unauthorized"}}
    end
  end

  def handle_in("echo", payload, socket) do
    {:reply, {:ok, payload}, socket}
  end

  def handle_in("trigger", payload, socket) do
    broadcast(socket, "broadcast", payload)
    {:noreply, socket}
  end

  # Add authorization logic here as required.
  defp authorized?(%{"auth" => "secret"}), do: true
  defp authorized?(_), do: false

  alias MockServerWeb.MockPresence, as: Presence
  alias MockServer.Counter

  def join("mock:presence", _payload, socket) do
    send(self(), :after_join)
    {:ok, assign(socket, :user_id, Counter.increment())}
  end

  def handle_info(:after_join, socket) do
    push(socket, "presence_state", Presence.list(socket))

    {:ok, _} =
      Presence.track(socket, socket.assigns.user_id, %{
        online_at: inspect(System.system_time(:second))
      })

    {:noreply, socket}
  end
end
