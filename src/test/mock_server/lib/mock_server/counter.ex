defmodule MockServer.Counter do
  use Agent

  def start_link(_) do
    Agent.start_link(fn -> 0 end, name: __MODULE__)
  end

  def value do
    Agent.get(__MODULE__, & &1)
  end

  def increment do
    Agent.get_and_update(__MODULE__, &({&1, &1 + 1}))
  end
end
