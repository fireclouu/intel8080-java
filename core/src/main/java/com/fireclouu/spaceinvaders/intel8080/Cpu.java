package com.fireclouu.spaceinvaders.intel8080;

public class Cpu {
    public interface IoHandler {
        short in(int port);
        void out(int port, short value);
    }

    private final Mmu mmu;
    private IoHandler ioHandler;

    // SOURCES: superzazu
    private final static byte[] OPCODES_CYCLES = {    //  0   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
            4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,  // 0
            4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,  // 1
            4, 10, 16, 5, 5, 5, 7, 4, 4, 10, 16, 5, 5, 5, 7, 4,  // 2
            4, 10, 13, 5, 10, 10, 10, 4, 4, 10, 13, 5, 5, 5, 7, 4,  // 3
            5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,  // 4
            5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,  // 5
            5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,  // 6
            7, 7, 7, 7, 7, 7, 7, 7, 5, 5, 5, 5, 5, 5, 7, 5,  // 7
            4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,  // 8
            4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,  // 9
            4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,  // A
            4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,  // B
            5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11, // C
            5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11, // D
            5, 10, 10, 18, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11, // E
            5, 10, 10, 4, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11  // F
    };

    private final Flags cc;
    ///  REGISTERS  ///
    private short b, c, d, e, h, l, a;
    ///  16-BIT REGISTER ADDRESSES  ///
    private int pc, sp;
    ///  INTERRUPT  ///
    private boolean hasInterrupt;
    // EI only takes effect after the instruction that follows it
    private boolean isEiDelay;
    // requested RST vector, held until interrupts are enabled; -1 if none
    private int pendingInterrupt;

    // flag positions
    private final short PSW_FLAG_POS_CY = 0b00000001; // 1
    private final short PSW_FLAG_POS_PA = 0b00000100; // 4
    private final short PSW_FLAG_POS_AC = 0b00010000; // 16 
    private final short PSW_FLAG_POS_ZE = 0b01000000; // 64
    private final short PSW_FLAG_POS_SN = 0b10000000; // 128

    public Cpu(Mmu mmu) {
        this.mmu = mmu;
        cc = new Flags();
        init();
    }

    public void init() {
        b = 0;
        c = 0;
        d = 0;
        e = 0;
        h = 0;
        l = 0;
        a = 0;

        pc = 0;
        sp = 0;

        hasInterrupt = false;
        isEiDelay = false;
        pendingInterrupt = -1;
        cc.init();
    }

    public byte step() {
        if (pendingInterrupt >= 0 && hasInterrupt && !isEiDelay) {
            serviceInterrupt();
            return 11; // same as RST
        }
        isEiDelay = false;

        int opcode = mmu.readMemory(pc);
        byte cycles = OPCODES_CYCLES[opcode];

        switch (opcode) {
            case 0x01:
                b = mmu.readMemory(pc + 2);
                c = mmu.readMemory(pc + 1);
                pc += 3;
                break; // LXI B, D16
            case 0x02:
                instr_sta(b, c);
                pc++;
                break; // STAX B
            case 0x09:
                instr_dad(b, c);
                pc++;
                break; //DAD B
            case 0x0a:
                instr_lda(b, c);
                pc++;
                break; // LDAX B
            case 0x11:
                d = mmu.readMemory(pc + 2);
                e = mmu.readMemory(pc + 1);
                pc += 3;
                break; // LXI D, D16
            case 0x12:
                instr_sta(d, e);
                pc++;
                break; // STAX D
            case 0x19:
                instr_dad(d, e);
                pc++;
                break; //DAD D
            case 0x1a:
                instr_lda(d, e);
                pc++;
                break; // LDAX D
            case 0x21:
                h = mmu.readMemory(pc + 2);
                l = mmu.readMemory(pc + 1);
                pc += 3;
                break; // LXI H, D16
            case 0x22:
                instr_shld(pc);
                pc += 3;
                break; // SHLD adr
            case 0x27:
                // SOURCE: superzazu
                // get least significant nibble and add 6 if >9
                // same as most significant nibble
                byte cy = cc.cy;
                short correction = 0;
                short lsb = (short) (a & 0xf);
                short msb = (short) (a >> 4);
                if (cc.ac == 1 || lsb > 9) {
                    correction += 0x06;
                }
                if (cc.cy == 1 || msb > 9 || (msb == 9 && lsb > 9)) {
                    correction += 0x60;
                    cy = 1;
                }
                instr_add(correction, 0);
                cc.cy = cy;
                pc++;
                break; // DAA
            case 0x29:
                instr_dad(h, l);
                pc++;
                break; //DAD H
            case 0x2a:
                instr_lhld(pc);
                pc += 3;
                break; // LHLD adr
            case 0x2f:
                a = (short) ((~a & 0xff));
                pc++;
                break; // CMA
            case 0x31:
                sp = ((mmu.readMemory(pc + 2) << 8) | mmu.readMemory(pc + 1));
                pc += 3;
                break; // LXI SP, D16
            case 0x32:
                instr_sta(mmu.readMemory(pc + 2), mmu.readMemory(pc + 1));
                pc += 3;
                break; // STA adr
            case 0x39:
                instr_dad(sp);
                pc++;
                break; //DAD SP
            case 0x3a:
                instr_lda(mmu.readMemory(pc + 2), mmu.readMemory(pc + 1));
                pc += 3;
                break; // LDA adr
            case 0xeb:
                instr_xchg();
                pc++;
                break; // XCHG (HL to DE vice-versa)

            // IMMEDIATE
            case 0x06:
                b = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI B, D8
            case 0x0e:
                c = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI C, D8
            case 0x16:
                d = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI D, D8
            case 0x1e:
                e = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI E, D8
            case 0x26:
                h = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI H, D8
            case 0x2e:
                l = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI L, D8
            case 0x36:
                mmu.writeMemory(get_pair_hl(), mmu.readMemory(pc + 1));
                pc += 2;
                break; // MVI M, D8
            case 0x3e:
                a = mmu.readMemory(pc + 1);
                pc += 2;
                break; // MVI A, D8

            // B
            case 0x40:
                pc++;
                break; // MOV B, B
            case 0x41:
                b = c;
                pc++;
                break; // MOV B, C
            case 0x42:
                b = d;
                pc++;
                break; // MOV B, D
            case 0x43:
                b = e;
                pc++;
                break; // MOV B, E
            case 0x44:
                b = h;
                pc++;
                break; // MOV B, H
            case 0x45:
                b = l;
                pc++;
                break; // MOV B, L
            case 0x46:
                b = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV B, M
            case 0x47:
                b = a;
                pc++;
                break; // MOV B, A

            // C
            case 0x48:
                c = b;
                pc++;
                break; // MOV C, B
            case 0x49:
                pc++;
                break; // MOV C, C
            case 0x4a:
                c = d;
                pc++;
                break; // MOV C, D
            case 0x4b:
                c = e;
                pc++;
                break; // MOV C, E
            case 0x4c:
                c = h;
                pc++;
                break; // MOV C, H
            case 0x4d:
                c = l;
                pc++;
                break; // MOV C, L
            case 0x4e:
                c = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV C, M
            case 0x4f:
                c = a;
                pc++;
                break; // MOV C, A

            // D
            case 0x50:
                d = b;
                pc++;
                break; // MOV D, B
            case 0x51:
                d = c;
                pc++;
                break; // MOV D, C
            case 0x52:
                pc++;
                break; // MOV D, D
            case 0x53:
                d = e;
                pc++;
                break; // MOV D, E
            case 0x54:
                d = h;
                pc++;
                break; // MOV D, H
            case 0x55:
                d = l;
                pc++;
                break; // MOV D, L
            case 0x56:
                d = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV D, M
            case 0x57:
                d = a;
                pc++;
                break; // MOV D, A

            // E
            case 0x58:
                e = b;
                pc++;
                break; // MOV E, B
            case 0x59:
                e = c;
                pc++;
                break; // MOV E, C
            case 0x5a:
                e = d;
                pc++;
                break; // MOV E, D
            case 0x5b:
                pc++;
                break; // MOV E, E
            case 0x5c:
                e = h;
                pc++;
                break; // MOV E, H
            case 0x5d:
                e = l;
                pc++;
                break; // MOV E, L
            case 0x5e:
                e = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV E, M
            case 0x5f:
                e = a;
                pc++;
                break; // MOV E, A

            // H
            case 0x60:
                h = b;
                pc++;
                break; // MOV H, B
            case 0x61:
                h = c;
                pc++;
                break; // MOV H, C
            case 0x62:
                h = d;
                pc++;
                break; // MOV H, D
            case 0x63:
                h = e;
                pc++;
                break; // MOV H, E
            case 0x64:
                pc++;
                break; // MOV H, H
            case 0x65:
                h = l;
                pc++;
                break; // MOV H, L
            case 0x66:
                h = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV H, M
            case 0x67:
                h = a;
                pc++;
                break; // MOV H, A

            // L
            case 0x68:
                l = b;
                pc++;
                break; // MOV L, B
            case 0x69:
                l = c;
                pc++;
                break; // MOV L, C
            case 0x6a:
                l = d;
                pc++;
                break; // MOV L, D
            case 0x6b:
                l = e;
                pc++;
                break; // MOV L, E
            case 0x6c:
                l = h;
                pc++;
                break; // MOV L, H
            case 0x6d:
                pc++;
                break; // MOV L, L
            case 0x6e:
                l = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV L, M
            case 0x6f:
                l = a;
                pc++;
                break; // MOV L, A

            // MEMORY
            case 0x70:
                mmu.writeMemory(get_pair_hl(), b);
                pc++;
                break; // MOV M, B
            case 0x71:
                mmu.writeMemory(get_pair_hl(), c);
                pc++;
                break; // MOV M, C
            case 0x72:
                mmu.writeMemory(get_pair_hl(), d);
                pc++;
                break; // MOV M, D
            case 0x73:
                mmu.writeMemory(get_pair_hl(), e);
                pc++;
                break; // MOV M, E
            case 0x74:
                mmu.writeMemory(get_pair_hl(), h);
                pc++;
                break; // MOV M, H
            case 0x75:
                mmu.writeMemory(get_pair_hl(), l);
                pc++;
                break; // MOV M, L
            case 0x77:
                mmu.writeMemory(get_pair_hl(), a);
                pc++;
                break; // MOV M, A

            // A
            case 0x78:
                a = b;
                pc++;
                break; // MOV A, B
            case 0x79:
                a = c;
                pc++;
                break; // MOV A, C
            case 0x7a:
                a = d;
                pc++;
                break; // MOV A, D
            case 0x7b:
                a = e;
                pc++;
                break; // MOV A, E
            case 0x7c:
                a = h;
                pc++;
                break; // MOV A, H
            case 0x7d:
                a = l;
                pc++;
                break; // MOV A, L
            case 0x7e:
                a = mmu.readMemory(get_pair_hl());
                pc++;
                break; // MOV A, M
            case 0x7f:
                pc++;
                break; // MOV A, A

            // ADD
            case 0x80:
                instr_add(b, 0);
                pc++;
                break; // ADD B
            case 0x81:
                instr_add(c, 0);
                pc++;
                break; // ADD C
            case 0x82:
                instr_add(d, 0);
                pc++;
                break; // ADD D
            case 0x83:
                instr_add(e, 0);
                pc++;
                break; // ADD E
            case 0x84:
                instr_add(h, 0);
                pc++;
                break; // ADD H
            case 0x85:
                instr_add(l, 0);
                pc++;
                break; // ADD L
            case 0x86:
                instr_add(mmu.readMemory(get_pair_hl()), 0);
                pc++;
                break; // ADD M
            case 0x87:
                instr_add(a, 0);
                pc++;
                break; // ADD A

            // ADC
            case 0x88:
                instr_add(b, cc.cy);
                pc++;
                break; // ADC B
            case 0x89:
                instr_add(c, cc.cy);
                pc++;
                break; // ADC C
            case 0x8a:
                instr_add(d, cc.cy);
                pc++;
                break; // ADC D
            case 0x8b:
                instr_add(e, cc.cy);
                pc++;
                break; // ADC E
            case 0x8c:
                instr_add(h, cc.cy);
                pc++;
                break; // ADC H
            case 0x8d:
                instr_add(l, cc.cy);
                pc++;
                break; // ADC L
            case 0x8e:
                instr_add(mmu.readMemory(get_pair_hl()), cc.cy);
                pc++;
                break; // ADC M
            case 0x8f:
                instr_add(a, cc.cy);
                pc++;
                break; // ADC A

            // SUB
            case 0x90:
                instr_sub(b, 0);
                pc++;
                break; // SUB B
            case 0x91:
                instr_sub(c, 0);
                pc++;
                break; // SUB C
            case 0x92:
                instr_sub(d, 0);
                pc++;
                break; // SUB D
            case 0x93:
                instr_sub(e, 0);
                pc++;
                break; // SUB E
            case 0x94:
                instr_sub(h, 0);
                pc++;
                break; // SUB H
            case 0x95:
                instr_sub(l, 0);
                pc++;
                break; // SUB L
            case 0x96:
                instr_sub(mmu.readMemory(get_pair_hl()), 0);
                pc++;
                break; // SUB M
            case 0x97:
                instr_sub(a, 0);
                pc++;
                break; // SUB A

            // SBB
            case 0x98:
                instr_sub(b, cc.cy);
                pc++;
                break; // SBB B
            case 0x99:
                instr_sub(c, cc.cy);
                pc++;
                break; // SBB C
            case 0x9a:
                instr_sub(d, cc.cy);
                pc++;
                break; // SBB D
            case 0x9b:
                instr_sub(e, cc.cy);
                pc++;
                break; // SBB E
            case 0x9c:
                instr_sub(h, cc.cy);
                pc++;
                break; // SBB H
            case 0x9d:
                instr_sub(l, cc.cy);
                pc++;
                break; // SBB L
            case 0x9e:
                instr_sub(mmu.readMemory(get_pair_hl()), cc.cy);
                pc++;
                break; // SBB M
            case 0x9f:
                instr_sub(a, cc.cy);
                pc++;
                break; // SBB A

            // ANA
            case 0xa0:
                instr_ana(b);
                pc++;
                break; // ANA B
            case 0xa1:
                instr_ana(c);
                pc++;
                break; // ANA C
            case 0xa2:
                instr_ana(d);
                pc++;
                break; // ANA D
            case 0xa3:
                instr_ana(e);
                pc++;
                break; // ANA E
            case 0xa4:
                instr_ana(h);
                pc++;
                break; // ANA H
            case 0xa5:
                instr_ana(l);
                pc++;
                break; // ANA L
            case 0xa6:
                instr_ana(mmu.readMemory(get_pair_hl()));
                pc++;
                break; // ANA M
            case 0xa7:
                instr_ana(a);
                pc++;
                break; // ANA A

            // XRA
            case 0xa8:
                instr_xra(b);
                pc++;
                break; // XRA B
            case 0xa9:
                instr_xra(c);
                pc++;
                break; // XRA C
            case 0xaa:
                instr_xra(d);
                pc++;
                break; // XRA D
            case 0xab:
                instr_xra(e);
                pc++;
                break; // XRA E
            case 0xac:
                instr_xra(h);
                pc++;
                break; // XRA H
            case 0xad:
                instr_xra(l);
                pc++;
                break; // XRA L
            case 0xae:
                instr_xra(mmu.readMemory(get_pair_hl()));
                pc++;
                break; // XRA M
            case 0xaf:
                instr_xra(a);
                pc++;
                break; // XRA A

            // ORA
            case 0xb0:
                instr_ora(b);
                pc++;
                break; // ORA B
            case 0xb1:
                instr_ora(c);
                pc++;
                break; // ORA C
            case 0xb2:
                instr_ora(d);
                pc++;
                break; // ORA D
            case 0xb3:
                instr_ora(e);
                pc++;
                break; // ORA E
            case 0xb4:
                instr_ora(h);
                pc++;
                break; // ORA H
            case 0xb5:
                instr_ora(l);
                pc++;
                break; // ORA L
            case 0xb6:
                instr_ora(mmu.readMemory(get_pair_hl()));
                pc++;
                break; // ORA M
            case 0xb7:
                instr_ora(a);
                pc++;
                break; // ORA A

            // CMP
            case 0xb8:
                instr_cmp(b);
                pc++;
                break; // CMP B
            case 0xb9:
                instr_cmp(c);
                pc++;
                break; // CMP C
            case 0xba:
                instr_cmp(d);
                pc++;
                break; // CMP D
            case 0xbb:
                instr_cmp(e);
                pc++;
                break; // CMP E
            case 0xbc:
                instr_cmp(h);
                pc++;
                break; // CMP H
            case 0xbd:
                instr_cmp(l);
                pc++;
                break; // CMP L
            case 0xbe:
                instr_cmp(mmu.readMemory(get_pair_hl()));
                pc++;
                break; // CMP M
            case 0xbf:
                instr_cmp(a);
                pc++;
                break; // CMP A

            // INR
            case 0x04:
                b = instr_inr(b);
                pc++;
                break; // INR B
            case 0x0c:
                c = instr_inr(c);
                pc++;
                break; // INR C
            case 0x14:
                d = instr_inr(d);
                pc++;
                break; // INR D
            case 0x1c:
                e = instr_inr(e);
                pc++;
                break; // INR E
            case 0x24:
                h = instr_inr(h);
                pc++;
                break; // INR H
            case 0x2c:
                l = instr_inr(l);
                pc++;
                break; // INR L
            case 0x34:
                mmu.writeMemory(get_pair_hl(), instr_inr(mmu.readMemory(get_pair_hl())));
                pc++;
                break; // INR M
            case 0x3c:
                a = instr_inr(a);
                pc++;
                break; // INR A

            // DCR
            case 0x05:
                b = instr_dcr(b);
                pc++;
                break; // DCR B
            case 0x0d:
                c = instr_dcr(c);
                pc++;
                break; // DCR C
            case 0x15:
                d = instr_dcr(d);
                pc++;
                break; // DCR D
            case 0x1d:
                e = instr_dcr(e);
                pc++;
                break; // DCR E
            case 0x25:
                h = instr_dcr(h);
                pc++;
                break; // DCR H
            case 0x2d:
                l = instr_dcr(l);
                pc++;
                break; // DCR L
            case 0x35:
                mmu.writeMemory(get_pair_hl(), instr_dcr(mmu.readMemory(get_pair_hl())));
                pc++;
                break; // DCR M
            case 0x3d:
                a = instr_dcr(a);
                pc++;
                break; // DCR A

            // INX
            case 0x03:
                set_pair_bc(get_pair_bc() + 1);
                pc++;
                break; // INX B
            case 0x13:
                set_pair_de(get_pair_de() + 1);
                pc++;
                break; // INX D
            case 0x23:
                set_pair_hl(get_pair_hl() + 1);
                pc++;
                break; // INX H
            case 0x33:
                sp = (sp + 1) & 0xffff;
                pc++;
                break; // INX SP

            // DCX
            case 0x0b:
                set_pair_bc(get_pair_bc() - 1);
                pc++;
                break; // DCX B
            case 0x1b:
                set_pair_de(get_pair_de() - 1);
                pc++;
                break; // DCX D
            case 0x2b:
                set_pair_hl(get_pair_hl() - 1);
                pc++;
                break; // DCX H
            case 0x3b:
                sp = (sp - 1) & 0xffff;
                pc++;
                break; // DCX SP

            // ROTATES
            case 0x07:
                instr_rlc();
                pc++;
                break; // RLC
            case 0x0f:
                instr_rrc();
                pc++;
                break; // RRC
            case 0x17:
                instr_ral();
                pc++;
                break; // RAL
            case 0x1f:
                instr_rar();
                pc++;
                break; // RAR

            // CARRY FLAG
            case 0x37:
                cc.cy = 1;
                pc++;
                break; // STC
            case 0x3f:
                cc.cy = (cc.cy == 1) ? (byte) 0 : 1;
                pc++;
                break; // CMC

            // ALU (IMMEDIATE)
            case 0xc6:
                instr_add(mmu.readMemory(pc + 1), 0);
                pc += 2;
                break; // ADI D8
            case 0xce:
                instr_add(mmu.readMemory(pc + 1), cc.cy);
                pc += 2;
                break; // ACI D8
            case 0xd6:
                instr_sub(mmu.readMemory(pc + 1), 0);
                pc += 2;
                break; // SUI D8
            case 0xde:
                instr_sub(mmu.readMemory(pc + 1), cc.cy);
                pc += 2;
                break; // SBI D8
            case 0xe6:
                instr_ana(mmu.readMemory(pc + 1));
                pc += 2;
                break; // ANI D8
            case 0xee:
                instr_xra(mmu.readMemory(pc + 1));
                pc += 2;
                break; // XRI D8
            case 0xf6:
                instr_ora(mmu.readMemory(pc + 1));
                pc += 2;
                break; // ORI D8
            case 0xfe:
                instr_cmp(mmu.readMemory(pc + 1));
                pc += 2;
                break; // CPI D8

            // JUMPS
            case 0xc3:
                instr_jmp(pc);
                break; // JMP adr
            case 0xc9:
                instr_ret();
                break; // RET
            case 0xcd:
                instr_call(pc);
                break; // CALL adr
            case 0xe9:
                pc = get_pair_hl();
                break; // PCHL

            // RET (conditional)
            case 0xc0:
                if (cc.z == 0) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RNZ
            case 0xc8:
                if (cc.z == 1) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RZ
            case 0xd0:
                if (cc.cy == 0) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RNC
            case 0xd8:
                if (cc.cy == 1) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RC
            case 0xe0:
                if (cc.p == 0) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RPO
            case 0xe8:
                if (cc.p == 1) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RPE
            case 0xf0:
                if (cc.s == 0) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RP
            case 0xf8:
                if (cc.s == 1) {
                    instr_ret();
                    cycles = 11;
                } else {
                    pc++;
                    cycles = 5;
                }
                break; // RM

            // JMP (conditional)
            case 0xc2:
                if (cc.z == 0) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JNZ adr
            case 0xca:
                if (cc.z == 1) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JZ adr
            case 0xd2:
                if (cc.cy == 0) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JNC adr
            case 0xda:
                if (cc.cy == 1) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JC adr
            case 0xe2:
                if (cc.p == 0) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JPO adr
            case 0xea:
                if (cc.p == 1) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JPE adr
            case 0xf2:
                if (cc.s == 0) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JP adr
            case 0xfa:
                if (cc.s == 1) {
                    instr_jmp(pc);
                } else {
                    pc += 3;
                }
                break; // JM adr

            // CALL (conditional)
            case 0xc4:
                if (cc.z == 0) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CNZ adr
            case 0xcc:
                if (cc.z == 1) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CZ adr
            case 0xd4:
                if (cc.cy == 0) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CNC adr
            case 0xdc:
                if (cc.cy == 1) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CC adr
            case 0xe4:
                if (cc.p == 0) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CPO adr
            case 0xec:
                if (cc.p == 1) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CPE adr
            case 0xf4:
                if (cc.s == 0) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CP adr
            case 0xfc:
                if (cc.s == 1) {
                    instr_call(pc);
                    cycles = 17;
                } else {
                    pc += 3;
                    cycles = 11;
                }
                break; // CM adr

            // POP
            case 0xc1:
                set_pair_bc(instr_pop());
                pc++;
                break; // POP B
            case 0xd1:
                set_pair_de(instr_pop());
                pc++;
                break; // POP D
            case 0xe1:
                set_pair_hl(instr_pop());
                pc++;
                break; // POP H
            case 0xf1:
                pop_psw();
                pc++;
                break; // POP PSW

            // PUSH
            case 0xc5:
                instr_push(get_pair_bc());
                pc++;
                break; // PUSH B
            case 0xd5:
                instr_push(get_pair_de());
                pc++;
                break; // PUSH D
            case 0xe5:
                instr_push(get_pair_hl());
                pc++;
                break; // PUSH H
            case 0xf5:
                push_psw();
                pc++;
                break; // PUSH PSW

            // XTHL, SPHL
            case 0xe3:
                instr_xthl();
                pc++;
                break; // XTHL
            case 0xf9:
                instr_sphl(get_pair_hl());
                pc++;
                break; // SPHL

            // RST
            case 0xc7:
                instr_call(0x00);
                break; // RST 0
            case 0xcf:
                instr_call(0x08);
                break; // RST 1
            case 0xd7:
                instr_call(0x10);
                break; // RST 2
            case 0xdf:
                instr_call(0x18);
                break; // RST 3
            case 0xe7:
                instr_call(0x20);
                break; // RST 4
            case 0xef:
                instr_call(0x28);
                break; // RST 5
            case 0xf7:
                instr_call(0x30);
                break; // RST 6
            case 0xff:
                instr_call(0x38);
                break; // RST 7

            // INTERRUPTS
            case 0xf3:
                hasInterrupt = false;
                pc++;
                break; // DI
            case 0xfb:
                hasInterrupt = true;
                isEiDelay = true;
                pc++;
                break; // EI

            // I/O
            case 0xd3:
                if (ioHandler != null) ioHandler.out(mmu.readMemory(pc + 1), a);
                pc += 2;
                break; // OUT D8
            case 0xdb:
                if (ioHandler != null) a = (short) (ioHandler.in(mmu.readMemory(pc + 1)) & 0xff);
                pc += 2;
                break; // IN D8 (Load I/O to Accumulator)

            // TERMINATE
            case 0x76:
                System.exit(0);
                pc++;
                break; // HLT

            case 0x00:
            case 0x08:
            case 0x10:
            case 0x18:
            case 0x20:
            case 0x28:
            case 0x38:
            case 0xcb:
            case 0xd9:
            case 0xdd:
            case 0xed:
            case 0xfd:
                pc++;
                break;
        }

        pc &= 0xffff;
        return cycles;
    }

    public void setIoHandler(IoHandler ioHandler) {
        this.ioHandler = ioHandler;
    }

    /// INTERRUPT
    public void requestInterrupt(int vectorAddress) {
        // latched, serviced on the next step() once INTE allows it
        pendingInterrupt = vectorAddress;
    }

    private void serviceInterrupt() {
        int vectorAddress = pendingInterrupt;
        pendingInterrupt = -1;
        mmu.writeMemory(sp - 1, (short) ((pc & 0xff00) >> 8));
        mmu.writeMemory(sp - 2, (short) (pc & 0xff));
        sp = (sp - 2) & 0xffff;
        pc = vectorAddress;
        hasInterrupt = false;
    }

    // REGISTER PAIRS
    private int get_pair_bc() {
        return (b << 8) | c;
    }

    private void set_pair_bc(int val) {
        b = (short) ((val >> 8) & 0xff);
        c = (short) (val & 0xff);
    }

    private int get_pair_de() {
        return (d << 8) | e;
    }

    private void set_pair_de(int val) {
        d = (short) ((val >> 8) & 0xff);
        e = (short) (val & 0xff);
    }

    private int get_pair_hl() {
        return (h << 8) | l;
    }

    private void set_pair_hl(int val) {
        h = (short) ((val >> 8) & 0xff);
        l = (short) (val & 0xff);
    }

    // ALUOP
    // ADD, ADC, ADI, ACI
    private void instr_add(int var, int cy) {
        short res = (short) ((a + (var & 0xff) + cy) & 0xff);
        cc.cy = checkCarry(8, a, (var & 0xff), cy);
        cc.ac = checkCarry(4, a, (var & 0xff), cy);
        flagsZSP(res);
        a = res;
    }

    // ANA, ANI
    private void instr_ana(int var) {
        short res = (short) ((a & var) & 0xff);
        cc.cy = 0;
        cc.ac = ((a | var) & 0x8) != 0 ? (byte) 1 : 0;
        flagsZSP(res);
        a = res;
    }

    // CMP, CMI
    private void instr_cmp(int var) {
        // (two's) complement
        int res = (a - var) & 0xffff;
        cc.cy = (res >> 8) != 0 ? (byte) 1 : 0;
        cc.ac = (~(a ^ res ^ var) & 0x10) != 0 ? (byte) 1 : 0;
        flagsZSP(res & 0xff);
    }

    // ORA, ORI
    private void instr_ora(int var) {
        a |= (short) (var & 0xff);
        cc.cy = cc.ac = 0;
        flagsZSP(a);
    }

    // SUB, SBB, SUI, SBI
    private void instr_sub(int var, int cy) {
        int inv_var = ~var;
        instr_add(inv_var, (~cy & 0x1));
        cc.cy = (byte) (~cc.cy & 0x1);
    }

    // XRA, XRI
    private void instr_xra(int var) {
        a ^= (short) (var & 0xff);
        cc.cy = cc.ac = 0;
        flagsZSP(a);
    }

    private short instr_inr(int var) {
        cc.ac = checkCarry(4, var, 1, 0);
        short res = (short) ((var + 1) & 0xff);
        flagsZSP(res);
        return res;
    }

    private short instr_dcr(int var) {
        cc.ac = checkCarry(4, var, -1, 0);
        short res = (short) ((var - 1) & 0xff);
        flagsZSP(res);
        return res;
    }

    // JUMPS
    private void instr_call(int pc) {
        int nextAddress = pc + 3;
        mmu.writeMemory(sp - 1, (short) ((nextAddress >> 8) & 0xff));
        mmu.writeMemory(sp - 2, (short) (nextAddress & 0xff));
        sp = (sp - 2) & 0xffff;
        instr_jmp(pc);
    }

    private void instr_dad(int... var) {
        int hl = (h << 8) | l;
        int pair;
        if (var.length == 2) {
            pair = (var[0] << 8) | var[1];
        } else {
            pair = var[0];
        }
        int res = hl + pair; // may result greater than 16 bit, raise CY if occurred
        cc.cy = ((res & 0xf_0000) > 0) ? (byte) 1 : 0; // cut all values from lower 16 bit and check if higher 16 bit has value
        h = (short) ((res & 0xff00) >> 8); // store higher 8-bit to H
        l = (short) (res & 0xff); // store lower  8-bit to L
    }

    private void instr_jmp(int pc) {
        this.pc = (mmu.readMemory(pc + 2) << 8) | mmu.readMemory(pc + 1);
    }

    private void instr_lda(int hi_nib, int lo_nib) {
        int address = (hi_nib << 8) | lo_nib;
        a = mmu.readMemory(address);
    }

    private void instr_lhld(int pc) {
        int address = (mmu.readMemory(pc + 2) << 8) | mmu.readMemory(pc + 1);
        h = mmu.readMemory(address + 1);
        l = mmu.readMemory(address);
    }

    private void pop_psw() {
        int PSW = mmu.readMemory(sp);

        cc.cy = ((PSW & PSW_FLAG_POS_CY) != 0) ? (byte) 1 : 0;
        cc.p = ((PSW & PSW_FLAG_POS_PA) != 0) ? (byte) 1 : 0;
        cc.ac = ((PSW & PSW_FLAG_POS_AC) != 0) ? (byte) 1 : 0;
        cc.z = ((PSW & PSW_FLAG_POS_ZE) != 0) ? (byte) 1 : 0;
        cc.s = ((PSW & PSW_FLAG_POS_SN) != 0) ? (byte) 1 : 0;
        a = mmu.readMemory(sp + 1);
        sp = (sp + 2) & 0xffff;
    }

    private int instr_pop() {
        int res = (mmu.readMemory(sp + 1) << 8) | mmu.readMemory(sp);
        sp = (sp + 2) & 0xffff;
        return res;
    }

    private void instr_push(int pair) {
        mmu.writeMemory(sp - 1, (short) (pair >> 8));
        mmu.writeMemory(sp - 2, (short) (pair & 0xff));
        sp = (sp - 2) & 0xffff;
    }

    private void push_psw() {
        mmu.writeMemory(sp - 1, a);
        // prepare variable higher than 0xff, but with 0's in bit 0-7
        // this way, it serves as flags' default state waiting to be flipped, like a template
        // also helps to retain flags proper positioning
        // skip pos 5 and 3, default 0 value
        int psw = (cc.s << 7) |   // place sign flag status on pos 7
                (cc.z << 6) |   // place zero flag status on pos 6
                (cc.ac << 4) |   // place aux. carry flag status on pos 4
                (cc.p << 2) |   // place parity flag status on pos 2
                (1 << 1) | (cc.cy);   // place carry flag status on pos 0
        mmu.writeMemory(sp - 2, (short) (psw & 0xff));
        sp = (sp - 2) & 0xffff;
    }

    private void instr_ral() {
        final byte cy = cc.cy;
        cc.cy = (byte) ((a >> 7) & 0xf);
        a = (short) (((a << 1) | cy) & 0xff);
    }

    private void instr_rar() {
        final byte cy = cc.cy;
        cc.cy = (byte) (a & 1);
        a = (short) (((a >> 1) | (cy << 7)) & 0xff);
    }

    private void instr_ret() {
        int address = mmu.readMemory(sp + 1) << 8 | mmu.readMemory(sp);
        sp = (sp + 2) & 0xffff;
        pc = address;
    }

    private void instr_rlc() {
        cc.cy = (byte) (a >> 7); // get bit 7 as carry
        a = (short) (((a << 1) | cc.cy) & 0xff); // rotate to left, wrapping its content
    }

    private void instr_rrc() {
        cc.cy = (byte) (a & 1); // get bit 0 as carry
        a = (short) ((a >> 1) | (cc.cy << 7) & 0xff); // rotate to right, wrapping its contents by placing bit 0 to bit 7
    }

    private void instr_shld(int pc) {
        int address = mmu.readMemory(pc + 2) << 8 | mmu.readMemory(pc + 1);
        mmu.writeMemory(address + 1, h);
        mmu.writeMemory(address, l);
    }

    private void instr_sphl(int address) {
        sp = address;
    }

    private void instr_sta(int hi_nib, int lo_nib) {
        int address = (hi_nib << 8) | lo_nib;
        mmu.writeMemory(address, a);
    }

    private void instr_xchg() {
        // SWAP H and D
        h = (short) (h + d);
        d = (short) (h - d);
        h = (short) (h - d);
        // SWAP L and E
        l = (short) (l + e);
        e = (short) (l - e);
        l = (short) (l - e);
    }

    private void instr_xthl() {
        h = (short) (h + mmu.readMemory(sp + 1));
        mmu.writeMemory(sp + 1, (short) (h - mmu.readMemory(sp + 1)));
        h = (short) (h - mmu.readMemory(sp + 1));
        l = (short) (l + mmu.readMemory(sp));
        mmu.writeMemory(sp, (short) (l - mmu.readMemory(sp)));
        l = (short) (l - mmu.readMemory(sp));
    }

    /// FLAGS
    private void flagsZSP(int result) {
        cc.z = ((result & 0xff) == 0) ? (byte) 1 : 0;
        cc.s = (byte) ((result >> 7) & 0x1);
        cc.p = flagParity(result & 0xff);
    }

    private byte flagParity(int result) {
        int res = 0;
        for (int i = 0; i < 8; i++) {
            if (((result >> i) & 0x1) == 1) res++;
        }
        return (res % 2 == 0) ? (byte) 1 : 0;
    }

    // SOURCE - superzazu
    // returns if there was a carry between bit "bit_no" and "bit_no - 1" when
    // executing "a + b + cy"
    private byte checkCarry(int bit_no, int a, int b, int cy) {
        int res = a + b + cy;
        int carry = res ^ a ^ b;
        return ((carry & (1 << bit_no)) != 0) ? (byte) 1 : 0;
    }

    public short getRegB() {
        return this.b;
    }
    public short getRegC() {
        return this.c;
    }
    public short getRegD() {
        return this.d;
    }
    public short getRegE() {
        return this.e;
    }
    public short getRegH() {
        return this.h;
    }
    public short getRegL() {
        return this.l;
    }
    public short getRegA() {
        return this.a;
    }
    public void setRegA(short a) {
        this.a = a;
    }
    public int getPC() {
        return this.pc;
    }
    public void setPC(int pc) {
        this.pc = pc & 0xffff;
    }
    public int getSP() {
        return this.sp;
    }

    public boolean hasInterrupt() {
        return this.hasInterrupt;
    }
}

class Flags {
    public byte z, s, p, cy, ac;

    public Flags() {
        init();
    }

    public void init() {
        z = 0;
        s = 0;
        p = 0;
        cy = 0;
        ac = 0;
    }
}
