import { Navigate, Route, Routes } from 'react-router';
import { RoomGate } from './features/room/components/RoomGate';
import { RoomPage } from './features/room/pages/RoomPage';

function App() {
  return (
    <Routes>
      <Route path="/" element={<RoomGate />} />
      <Route path="/rooms/:roomId" element={<RoomPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
