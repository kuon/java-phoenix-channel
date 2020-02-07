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
    broadcast socket, "broadcast", payload
    {:noreply, socket}
  end

  # Add authorization logic here as required.
  defp authorized?(%{"auth" => "secret"}), do: true
  defp authorized?(_), do: false
end
